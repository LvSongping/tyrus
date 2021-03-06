/*
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
*
* Copyright (c) 2011-2013 Oracle and/or its affiliates. All rights reserved.
*
* The contents of this file are subject to the terms of either the GNU
* General Public License Version 2 only ("GPL") or the Common Development
* and Distribution License("CDDL") (collectively, the "License").  You
* may not use this file except in compliance with the License.  You can
* obtain a copy of the License at
* http://glassfish.java.net/public/CDDL+GPL_1_1.html
* or packager/legal/LICENSE.txt.  See the License for the specific
* language governing permissions and limitations under the License.
*
* When distributing the software, include this License Header Notice in each
* file and include the License file at packager/legal/LICENSE.txt.
*
* GPL Classpath Exception:
* Oracle designates this particular file as subject to the "Classpath"
* exception as provided by Oracle in the GPL Version 2 section of the License
* file that accompanied this code.
*
* Modifications:
* If applicable, add the following below the License Header, with the fields
* enclosed by brackets [] replaced by your own identifying information:
* "Portions Copyright [year] [name of copyright owner]"
*
* Contributor(s):
* If you wish your version of this file to be governed by only the CDDL or
* only the GPL Version 2, indicate your decision by adding "[Contributor]
* elects to include this software in this distribution under the [CDDL or GPL
* Version 2] license."  If you don't indicate a single choice of license, a
* recipient has the option to distribute your version of this file under
* either the CDDL, the GPL Version 2 or to extend the choice of license to
* its licensees as provided above.  However, if you add GPL Version 2 code
* and therefore, elected the GPL Version 2 license, then the option applies
* only if the new code is made subject to such option by the copyright
* holder.
*/
package org.glassfish.tyrus.container.grizzly.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.DeploymentException;

import org.glassfish.tyrus.spi.ClientEngine;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

/**
 * Implementation of the WebSocket interface.
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class GrizzlyClientSocket {

    /**
     * Can be used as client-side user property to set proxy.
     * <p/>
     * Value is expected to be {@link String} and represent proxy URI. Protocol part is currently ignored
     * but must be present ({@link URI#URI(String)} is used for parsing).
     * <p/>
     * <pre>
     *     client.getProperties().put(GrizzlyClientSocket.PROXY_URI, "http://my.proxy.com:80");
     *     client.connectToServer(...);
     * </pre>
     *
     * @see javax.websocket.ClientEndpointConfig#getUserProperties()
     */
    public static final String PROXY_URI = "org.glassfish.tyrus.client.proxy";

    /**
     * Client-side property to set custom worker {@link ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String WORKER_THREAD_POOL_CONFIG = "org.glassfish.tyrus.client.grizzly.workerThreadPoolConfig";

    /**
     * Client-side property to set custom selector {@link ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String SELECTOR_THREAD_POOL_CONFIG = "org.glassfish.tyrus.client.grizzly.selectorThreadPoolConfig";

    private static final Logger LOGGER = Logger.getLogger(GrizzlyClientSocket.class.getName());

    private final List<Proxy> proxies = new ArrayList<Proxy>();

    private final URI uri;
    private final long timeoutMs;
    private final SSLEngineConfigurator clientSSLEngineConfigurator;
    private final ThreadPoolConfig workerThreadPoolConfig;
    private final ThreadPoolConfig selectorThreadPoolConfig;
    private final ClientEngine engine;

    private SocketAddress socketAddress;

    private TCPNIOTransport transport;

    /**
     * Create new instance.
     *
     * @param uri                         endpoint address.
     * @param timeoutMs                   TODO
     * @param engine                      engine used for this websocket communication
     * @param clientSSLEngineConfigurator ssl engine configurator
     */
    GrizzlyClientSocket(URI uri, long timeoutMs,
                        ClientEngine engine,
                        SSLEngineConfigurator clientSSLEngineConfigurator,
                        String proxyString,
                        ThreadPoolConfig workerThreadPoolConfig,
                        ThreadPoolConfig selectorThreadPoolConfig) {
        this.uri = uri;
        this.timeoutMs = timeoutMs;
        this.clientSSLEngineConfigurator = clientSSLEngineConfigurator;
        this.workerThreadPoolConfig = workerThreadPoolConfig;
        this.selectorThreadPoolConfig = selectorThreadPoolConfig;
        this.engine = engine;

        setProxy(proxyString);
    }

    /**
     * Connects to the given {@link URI}.
     */
    public void connect() throws IOException, DeploymentException {
        for (Proxy proxy : proxies) {
            try {
                transport = createTransport(workerThreadPoolConfig, selectorThreadPoolConfig);
                transport.start();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Transport failed to start.", e);
                throw e;
            }

            final TCPNIOConnectorHandler connectorHandler = new TCPNIOConnectorHandler(transport) {
            };

            connectorHandler.setSyncConnectTimeout(timeoutMs, TimeUnit.MILLISECONDS);

            GrizzlyFuture<Connection> connectionGrizzlyFuture;

            final ClientEngine.TimeoutHandler timeoutHandler = new ClientEngine.TimeoutHandler() {
                @Override
                public void handleTimeout() {
                    closeTransport();
                }
            };

            switch (proxy.type()) {
                case DIRECT:
                    connectorHandler.setProcessor(createFilterChain(engine, null, clientSSLEngineConfigurator, false, uri, timeoutHandler));

                    LOGGER.log(Level.CONFIG, String.format("Connecting to '%s' (no proxy).", uri));
                    connectionGrizzlyFuture = connectorHandler.connect(socketAddress);
                    break;
                default:
                    connectorHandler.setProcessor(createFilterChain(engine, null, clientSSLEngineConfigurator, true, uri, timeoutHandler));

                    LOGGER.log(Level.CONFIG, String.format("Connecting to '%s' via proxy '%s'.", uri, proxy));

                    // default ProxySelector always returns proxies with unresolved addresses.
                    SocketAddress address = proxy.address();
                    if (address instanceof InetSocketAddress) {
                        InetSocketAddress inetSocketAddress = (InetSocketAddress) address;
                        if (inetSocketAddress.isUnresolved()) {
                            // resolves the address.
                            address = new InetSocketAddress(inetSocketAddress.getHostName(), inetSocketAddress.getPort());
                        }
                    }

                    connectionGrizzlyFuture = connectorHandler.connect(address);
                    break;
            }

            try {
                final Connection connection = connectionGrizzlyFuture.get(timeoutMs, TimeUnit.MILLISECONDS);


                LOGGER.log(Level.CONFIG, String.format("Connected to '%s'.", connection.getPeerAddress()));
                return;
            } catch (InterruptedException interruptedException) {
                LOGGER.log(Level.CONFIG, String.format("Connection to '%s' failed.", uri), interruptedException);
                closeTransport();
            } catch (TimeoutException timeoutException) {
                LOGGER.log(Level.CONFIG, String.format("Connection to '%s' failed.", uri), timeoutException);
                closeTransport();
            } catch (ExecutionException exectionException) {
                LOGGER.log(Level.CONFIG, String.format("Connection to '%s' failed.", uri), exectionException);

                IOException ioException = null;
                final Throwable cause = exectionException.getCause();
                if ((cause != null) && (cause instanceof IOException)) {
                    ioException = (IOException) cause;
                    ProxySelector.getDefault().connectFailed(uri, socketAddress, ioException);

                }

                closeTransport();

                if (ioException != null) {
                    throw ioException;
                }
            }
        }

        throw new DeploymentException("Connection failed.");
    }

    private TCPNIOTransport createTransport(ThreadPoolConfig workerThreadPoolConfig, ThreadPoolConfig selectorThreadPoolConfig) {

        // TYRUS-188: lots of threads were created for every single client instance.
        TCPNIOTransportBuilder transportBuilder = TCPNIOTransportBuilder.newInstance();

        if (workerThreadPoolConfig == null) {
            transportBuilder.setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().setMaxPoolSize(2).setCorePoolSize(2));
        } else {
            transportBuilder.setWorkerThreadPoolConfig(workerThreadPoolConfig);
        }

        if (selectorThreadPoolConfig == null) {
            transportBuilder.setSelectorThreadPoolConfig(ThreadPoolConfig.defaultConfig().setMaxPoolSize(1).setCorePoolSize(1));
        } else {
            transportBuilder.setSelectorThreadPoolConfig(selectorThreadPoolConfig);
        }

        transportBuilder.setIOStrategy(WorkerThreadIOStrategy.getInstance());

        return transportBuilder.build();
    }

    private void setProxy(String proxyString) {
        URI proxyUri;
        try {
            if (proxyString != null) {
                proxyUri = new URI(proxyString);
                if (proxyUri.getHost() == null) {
                    LOGGER.log(Level.WARNING, String.format("Invalid proxy '%s'.", proxyString));
                } else {
                    // proxy set via properties
                    int proxyPort = proxyUri.getPort() == -1 ? 80 : proxyUri.getPort();
                    proxies.add(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUri.getHost(), proxyPort)));
                }
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, String.format("Invalid proxy '%s'.", proxyString), e);
        }

        // ProxySelector
        final ProxySelector proxySelector = ProxySelector.getDefault();

        // see WebSocket Protocol RFC, chapter 4.1.3: http://tools.ietf.org/html/rfc6455#section-4.1
        addProxies(proxySelector, uri, "socket", proxies);
        addProxies(proxySelector, uri, "https", proxies);
        addProxies(proxySelector, uri, "http", proxies);
        proxies.add(Proxy.NO_PROXY);

        // compute direct address in case no proxy is found
        int port = uri.getPort();
        if (port == -1) {
            String scheme = uri.getScheme();
            assert scheme != null && (scheme.equals("ws") || scheme.equals("wss"));
            if (scheme.equals("ws")) {
                port = 80;
            } else if (scheme.equals("wss")) {
                port = 443;
            }
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, String.format(String.format("Not using proxy for URI '%s'.", uri)));
        }
        socketAddress = new InetSocketAddress(uri.getHost(), port);
    }

    /**
     * Add proxies to supplied list. Proxies will be obtained via supplied {@link ProxySelector} instance.
     *
     * @param proxySelector proxy selector.
     * @param uri           original request {@link URI}.
     * @param scheme        scheme used for proxy selection.
     * @param proxies       list of proxies (found proxies will be added to this list).
     */
    private void addProxies(ProxySelector proxySelector, URI uri, String scheme, List<Proxy> proxies) {
        for (Proxy p : proxySelector.select(getProxyUri(uri, scheme))) {
            switch (p.type()) {
                case HTTP:
                    LOGGER.log(Level.FINE, String.format("Found proxy: '%s'", p));
                    proxies.add(p);
                    break;
                case SOCKS:
                    LOGGER.log(Level.INFO, String.format("Socks proxy is not supported, please file new issue at https://java.net/jira/browse/TYRUS. Proxy '%s' will be ignored.", p));
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Since standard Java {@link ProxySelector} does not support "ws" and "wss" schemes in {@link URI URIs},
     * we need to replace them by others ("socket", "https" or "http").
     *
     * @param wsUri  original {@link URI}.
     * @param scheme new scheme.
     * @return {@link URI} with updated scheme.
     */
    private URI getProxyUri(URI wsUri, String scheme) {
        try {
            return new URI(scheme, wsUri.getUserInfo(), wsUri.getHost(), wsUri.getPort(), wsUri.getPath(), wsUri.getQuery(), wsUri.getFragment());
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, String.format("Exception during generating proxy URI '%s'", wsUri), e);
            return wsUri;
        }
    }

    private static Processor createFilterChain(ClientEngine engine,
                                               SSLEngineConfigurator serverSSLEngineConfigurator,
                                               SSLEngineConfigurator clientSSLEngineConfigurator,
                                               boolean proxy,
                                               URI uri,
                                               ClientEngine.TimeoutHandler timeoutHandler) {
        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        Filter sslFilter = null;

        clientFilterChainBuilder.add(new TransportFilter());
        if (serverSSLEngineConfigurator != null || clientSSLEngineConfigurator != null) {
            sslFilter = new SSLFilter(serverSSLEngineConfigurator, clientSSLEngineConfigurator);
            if (proxy) {
                sslFilter = new FilterWrapper(sslFilter);
            }
            clientFilterChainBuilder.add(sslFilter);
        }
        clientFilterChainBuilder.add(new HttpClientFilter());
        clientFilterChainBuilder.add(new GrizzlyClientFilter(engine, proxy, sslFilter, uri, timeoutHandler));
        return clientFilterChainBuilder.build();
    }

    private void closeTransport() {
        if (transport != null) {
            try {
                transport.shutdownNow();
            } catch (IOException e) {
                Logger.getLogger(GrizzlyClientSocket.class.getName()).log(Level.INFO, "Exception thrown when closing Grizzly transport: " + e.getMessage(), e);
            }
        }
    }

    /**
     * {@link SSLFilter} wrapper used for proxied connections. SSL filter gets "enabled" after initial proxy communication,
     * so after connection is established and SSL layer should start handling reading/writing messages.
     */
    static class FilterWrapper implements Filter {

        private final Filter filter;
        private boolean enabled = false;

        FilterWrapper(Filter filter) {
            this.filter = filter;
        }

        public void enable() {
            this.enabled = true;
        }

        @Override
        public void onAdded(FilterChain filterChain) {
            filter.onAdded(filterChain);
        }

        @Override
        public void onRemoved(FilterChain filterChain) {
            filter.onRemoved(filterChain);
        }

        @Override
        public void onFilterChainChanged(FilterChain filterChain) {
            filter.onFilterChainChanged(filterChain);
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            if (enabled) {
                return filter.handleRead(ctx);
            } else {
                return ctx.getInvokeAction();
            }
        }

        @Override
        public NextAction handleWrite(FilterChainContext ctx) throws IOException {
            if (enabled) {
                return filter.handleWrite(ctx);
            } else {
                return ctx.getInvokeAction();
            }
        }

        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {
            return ctx.getInvokeAction();
        }

        @Override
        public NextAction handleAccept(FilterChainContext ctx) throws IOException {
            return ctx.getInvokeAction();
        }

        @Override
        public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event) throws IOException {
            if (enabled) {
                return filter.handleEvent(ctx, event);
            } else {
                return ctx.getInvokeAction();
            }
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws IOException {
            if (enabled) {
                return filter.handleClose(ctx);
            } else {
                return ctx.getInvokeAction();
            }
        }

        @Override
        public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
            if (enabled) {
                filter.exceptionOccurred(ctx, error);
            } else {
                ctx.getInvokeAction();
            }
        }
    }
}
