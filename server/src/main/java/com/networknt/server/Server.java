/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.server;

import com.networknt.config.Config;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.registry.Registry;
import com.networknt.registry.URL;
import com.networknt.registry.URLImpl;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.switcher.SwitcherUtil;
import com.networknt.utility.Constants;
import com.networknt.utility.Util;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.ServiceLoader;


public class Server {

    static final Logger logger = LoggerFactory.getLogger(Server.class);
    static final String CONFIG_NAME = "server";
    public static ServerConfig config = (ServerConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, ServerConfig.class);

    public final static TrustManager[] TRUST_ALL_CERTS = new X509TrustManager[] { new DummyTrustManager() };

    static protected boolean shutdownRequested = false;
    static Undertow server = null;
    static URL serviceHttpUrl;
    static URL serviceHttpsUrl;
    static Registry registry;

    static SSLContext sslContext;

    public static void main(final String[] args) {
        logger.info("server starts");
        // setup system property to redirect logs to logback.
        System.setProperty("org.jboss.logging.provider", "slf4j");
        start();
    }

    static public void start() {

        // add shutdown hook here.
        addDaemonShutdownHook();

        // add startup hooks here.
        final ServiceLoader<StartupHookProvider> startupLoaders = ServiceLoader.load(StartupHookProvider.class);
        for (final StartupHookProvider provider : startupLoaders) {
            provider.onStartup();
        }

        // application level service registry. only be used without docker container.
        if(config.enableRegistry) {
            // assuming that registry is defined in service.json, otherwise won't start server.
            registry = (Registry) SingletonServiceFactory.getBean(Registry.class);
            if(registry == null) throw new RuntimeException("Could not find registry instance in service map");
            InetAddress inetAddress = Util.getInetAddress();
            String ipAddress = inetAddress.getHostAddress();
            if(config.enableHttp) {
                serviceHttpUrl = new URLImpl("light", ipAddress, config.getHttpPort(), config.getServiceId());
                registry.register(serviceHttpUrl);
                if(logger.isInfoEnabled()) logger.info("register serviceHttpUrl " + serviceHttpUrl);
            }
            if(config.enableHttps) {
                serviceHttpsUrl = new URLImpl("light", ipAddress, config.getHttpsPort(), config.getServiceId());
                registry.register(serviceHttpsUrl);
                if(logger.isInfoEnabled()) logger.info("register serviceHttpsUrl " + serviceHttpsUrl);
            }
        }

        HttpHandler handler = null;

        // API routing handler or others handler implemented by application developer.
        final ServiceLoader<HandlerProvider> handlerLoaders = ServiceLoader.load(HandlerProvider.class);
        for (final HandlerProvider provider : handlerLoaders) {
            if (provider.getHandler() != null) {
                handler = provider.getHandler();
                break;
            }
        }
        if (handler == null) {
            logger.error("Unable to start the server - no route handler provider available in the classpath");
            return;
        }

        // Middleware Handlers plugged into the handler chain.
        final ServiceLoader<MiddlewareHandler> middlewareLoaders = ServiceLoader.load(MiddlewareHandler.class);
        logger.debug("found middlewareLoaders", middlewareLoaders);
        for (final MiddlewareHandler middlewareHandler : middlewareLoaders) {
            logger.info("Plugin: " + middlewareHandler.getClass().getName());
            if(middlewareHandler.isEnabled()) {
                handler = middlewareHandler.setNext(handler);
                middlewareHandler.register();
            }
        }

        Undertow.Builder builder = Undertow.builder();

        if(config.enableHttp) {
            builder.addHttpListener(config.getHttpPort(), config.getIp());
        }
        if(config.enableHttps) {
            sslContext = createSSLContext();
            builder.addHttpsListener(config.getHttpsPort(), config.getIp(), sslContext);
        }

        server = builder
                .setBufferSize(1024 * 16)
                .setIoThreads(Runtime.getRuntime().availableProcessors() * 2) //this seems slightly faster in some configurations
                .setSocketOption(Options.BACKLOG, 10000)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false) //don't send a keep-alive header for HTTP/1.1 requests, as it is not required
                .setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                .setHandler(Handlers.header(handler,
                        Headers.SERVER_STRING, "Light"))
                .setWorkerThreads(200)
                .build();
        server.start();

        if(logger.isInfoEnabled()) {
            if(config.enableHttp) {
                logger.info("Http Server started on ip:" + config.getIp() + " Port:" + config.getHttpPort());
            }
            if(config.enableHttps) {
                logger.info("Https Server started on ip:" + config.getIp() + " Port:" + config.getHttpsPort());
            }
        }

        if(config.enableRegistry) {
            // start heart beat if registry is enabled
            SwitcherUtil.setSwitcherValue(Constants.REGISTRY_HEARTBEAT_SWITCHER, true);
            if(logger.isInfoEnabled()) logger.info("Registry heart beat switcher is on");
        }
    }

    static public void stop() {
        if (server != null) server.stop();
    }

    // implement shutdown hook here.
    static public void shutdown() {

        // need to unregister the service
        if(config.enableRegistry && registry != null && config.enableHttp) {
            registry.unregister(serviceHttpUrl);
            if(logger.isInfoEnabled()) logger.info("unregister serviceHttpUrl " + serviceHttpUrl);
        }
        if(config.enableRegistry && registry != null && config.enableHttps) {
            registry.unregister(serviceHttpsUrl);
            if(logger.isInfoEnabled()) logger.info("unregister serviceHttpsUrl " + serviceHttpsUrl);
        }

        final ServiceLoader<ShutdownHookProvider> shutdownLoaders = ServiceLoader.load(ShutdownHookProvider.class);
        for (final ShutdownHookProvider provider : shutdownLoaders) {
            provider.onShutdown();
        }
        stop();
        logger.info("Cleaning up before server shutdown");
    }

    static protected void addDaemonShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                Server.shutdown();
            }
        });
    }

    private static KeyStore loadKeyStore() {
        String name = config.getKeystoreName();
        try (InputStream stream = Config.getInstance().getInputStreamFromFile(name)) {
            KeyStore loadedKeystore = KeyStore.getInstance("JKS");
            loadedKeystore.load(stream, config.getKeystorePass().toCharArray());
            return loadedKeystore;
        } catch (Exception e) {
            logger.error("Unable to load keystore " + name, e);
            throw new RuntimeException("Unable to load keystore " + name, e);
        }
    }

    protected static KeyStore loadTrustStore() {
        String name = config.getTruststoreName();
        try (InputStream stream = Config.getInstance().getInputStreamFromFile(name)) {
            KeyStore loadedKeystore = KeyStore.getInstance("JKS");
            loadedKeystore.load(stream, config.getTruststorePass().toCharArray());
            return loadedKeystore;
        } catch (Exception e) {
            logger.error("Unable to load truststore " + name, e);
            throw new RuntimeException("Unable to load truststore " + name, e);
        }
    }

    private static TrustManager[] buildTrustManagers(final KeyStore trustStore) {
        TrustManager[] trustManagers = null;
        if (trustStore == null) {
            try {
                TrustManagerFactory trustManagerFactory = TrustManagerFactory
                        .getInstance(KeyManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(trustStore);
                trustManagers = trustManagerFactory.getTrustManagers();
            }
            catch (NoSuchAlgorithmException | KeyStoreException e) {
                logger.error("Unable to initialise TrustManager[]", e);
                throw new RuntimeException("Unable to initialise TrustManager[]", e);
            }
        }
        else {
            trustManagers = TRUST_ALL_CERTS;
        }
        return trustManagers;
    }

    private static KeyManager[] buildKeyManagers(final KeyStore keyStore, char[] keyPass) {
        KeyManager[] keyManagers;
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory
                    .getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keyPass);
            keyManagers = keyManagerFactory.getKeyManagers();
        }
        catch (NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException e) {
            logger.error("Unable to initialise KeyManager[]", e);
            throw new RuntimeException("Unable to initialise KeyManager[]", e);
        }
        return keyManagers;
    }

    private static SSLContext createSSLContext() throws RuntimeException {
        try {
            KeyManager[] keyManagers = buildKeyManagers(loadKeyStore(), config.getKeyPass().toCharArray());
            TrustManager[] trustManagers;
            if(config.isEnableTwoWayTls()) {
                trustManagers = buildTrustManagers(loadTrustStore());
            } else {
                trustManagers = buildTrustManagers(null);
            }

            SSLContext sslContext;
            sslContext = SSLContext.getInstance("TLSv1");
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            logger.error("Unable to create SSLContext", e);
            throw new RuntimeException("Unable to create SSLContext", e);
        }
    }

}
