/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.tyrus.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;

import org.glassfish.tyrus.core.DataFrame;
import org.glassfish.tyrus.core.EndpointWrapper;
import org.glassfish.tyrus.core.FramingException;
import org.glassfish.tyrus.core.Handshake;
import org.glassfish.tyrus.core.HandshakeException;
import org.glassfish.tyrus.core.ProtocolHandler;
import org.glassfish.tyrus.core.RequestContext;
import org.glassfish.tyrus.core.TyrusEndpoint;
import org.glassfish.tyrus.core.TyrusExtension;
import org.glassfish.tyrus.core.TyrusRemoteEndpoint;
import org.glassfish.tyrus.core.TyrusWebSocket;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.core.Version;
import org.glassfish.tyrus.core.WebSocket;
import org.glassfish.tyrus.spi.ClientEngine;
import org.glassfish.tyrus.spi.Connection;
import org.glassfish.tyrus.spi.ReadHandler;
import org.glassfish.tyrus.spi.UpgradeRequest;
import org.glassfish.tyrus.spi.UpgradeResponse;
import org.glassfish.tyrus.spi.Writer;

/**
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class TyrusClientEngine implements ClientEngine {

    public static final String INCOMING_BUFFER_SIZE = "org.glassfish.tyrus.incomingBufferSize";

    private static final Version DEFAULT_VERSION = Version.DRAFT17;
    private static final int BUFFER_STEP_SIZE = 256;

    private final ProtocolHandler protocolHandler = DEFAULT_VERSION.createHandler(true);
    private final EndpointWrapper endpointWrapper;
    private final ClientHandshakeListener listener;

    private Handshake clientHandShake = null;
    private volatile TimeoutHandler timeoutHandler = null;

    private int incomingBufferSize = 4194315; // 4M (payload) + 11 (frame overhead)

    /**
     * Create {@link org.glassfish.tyrus.spi.WebSocketEngine} instance based on passed {@link WebSocketContainer} and with configured maximal
     * incoming buffer size.
     *
     * @param endpointWrapper    TODO.
     * @param listener           TODO.
     * @param incomingBufferSize maximal incoming buffer size (this engine won't be able to process messages bigger
     *                           than this number. If null, default value will be used).
     */
    public TyrusClientEngine(EndpointWrapper endpointWrapper, ClientHandshakeListener listener, Integer incomingBufferSize) {
        if (incomingBufferSize != null) {
            this.incomingBufferSize = incomingBufferSize;
        }
        this.endpointWrapper = endpointWrapper;
        this.listener = listener;
    }

    @Override
    public UpgradeRequest createUpgradeRequest(URI uri, TimeoutHandler timeoutHandler) {
        this.timeoutHandler = timeoutHandler;
        clientHandShake = protocolHandler.createClientHandShake(RequestContext.Builder.create().requestURI(uri).build());

        ClientEndpointConfig config = (ClientEndpointConfig) endpointWrapper.getEndpointConfig();

        clientHandShake.setExtensions(config.getExtensions());
        clientHandShake.setSubProtocols(config.getPreferredSubprotocols());

        clientHandShake.prepareRequest();
        config.getConfigurator().beforeRequest(clientHandShake.getRequest().getHeaders());

        return clientHandShake.getRequest();
    }

    @Override
    public Connection processResponse(UpgradeResponse upgradeResponse, final Writer writer, final Connection.CloseListener closeListener) {

        try {
            clientHandShake.validateServerResponse(upgradeResponse);

            final TyrusWebSocket tyrusWebSocket = new TyrusWebSocket(protocolHandler, new TyrusEndpoint(endpointWrapper));
            final Session sessionForRemoteEndpoint = endpointWrapper.createSessionForRemoteEndpoint(
                    new TyrusRemoteEndpoint(tyrusWebSocket),
                    upgradeResponse.getFirstHeaderValue(HandshakeRequest.SEC_WEBSOCKET_PROTOCOL),
                    TyrusExtension.fromHeaders(upgradeResponse.getHeaders().get(HandshakeRequest.SEC_WEBSOCKET_EXTENSIONS)));

            ((ClientEndpointConfig) endpointWrapper.getEndpointConfig()).getConfigurator().afterResponse(upgradeResponse);

            protocolHandler.setWriter(writer);
            protocolHandler.setWebSocket(tyrusWebSocket);

            tyrusWebSocket.onConnect(this.clientHandShake.getRequest());

            listener.onSessionCreated(sessionForRemoteEndpoint);

            return new Connection() {

                private final ReadHandler readHandler = new TyrusReadHandler(protocolHandler, tyrusWebSocket, incomingBufferSize);

                @Override
                public ReadHandler getReadHandler() {
                    return readHandler;
                }

                @Override
                public Writer getWriter() {
                    return writer;
                }

                @Override
                public CloseListener getCloseListener() {
                    return closeListener;
                }

                @Override
                public void close(CloseReason reason) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        Logger.getLogger(this.getClass().getName()).log(Level.WARNING, e.getMessage(), e);
                    }
                }
            };
        } catch (HandshakeException e) {
            listener.onError(e);
            // TODO
//            content.getContent().clear();
//            return ctx.getStopAction();
            return null;
        }
    }

    /**
     * Get {@link TimeoutHandler} associated with current {@link ClientEngine} instance.
     *
     * @return timeout handler instance or {@code null} when not present.
     */
    public TimeoutHandler getTimeoutHandler() {
        return timeoutHandler;
    }

    /**
     * Called when response is received from the server.
     */
    public static interface ClientHandshakeListener {

        /**
         * Called when correct handshake response is received.
         *
         * @param session TODO.
         */
        public void onSessionCreated(Session session);


        /**
         * Called when an error is found in handshake response.
         *
         * @param exception error found during handshake response check.
         */
        public void onError(Throwable exception);
    }

    private static class TyrusReadHandler implements ReadHandler {

        private final int incomingBufferSize;
        private final ProtocolHandler handler;
        private final WebSocket webSocket;
        private ByteBuffer buffer = null;

        TyrusReadHandler(final ProtocolHandler handler, final WebSocket webSocket, int incomingBufferSize) {
            this.handler = handler;
            this.webSocket = webSocket;
            this.incomingBufferSize = incomingBufferSize;
        }

        @Override
        public void handle(ByteBuffer data) {
            try {
                if (data != null && data.hasRemaining()) {

                    if (buffer != null) {
                        data = Utils.appendBuffers(buffer, data, incomingBufferSize, BUFFER_STEP_SIZE);
                    } else {
                        int newSize = data.remaining();
                        if (newSize > incomingBufferSize) {
                            throw new IllegalArgumentException("Buffer overflow.");
                        } else {
                            final int roundedSize = (newSize % BUFFER_STEP_SIZE) > 0 ? ((newSize / BUFFER_STEP_SIZE) + 1) * BUFFER_STEP_SIZE : newSize;
                            final ByteBuffer result = ByteBuffer.allocate(roundedSize > incomingBufferSize ? newSize : roundedSize);
                            result.flip();
                            data = Utils.appendBuffers(result, data, incomingBufferSize, BUFFER_STEP_SIZE);
                        }
                    }

                    do {
                        final DataFrame result = handler.unframe(data);
                        if (result == null) {
                            buffer = data;
                            break;
                        } else {
                            result.respond(webSocket);
                        }
                    } while (true);
                }
            } catch (FramingException e) {
                e.printStackTrace();
                webSocket.onClose(new CloseReason(CloseReason.CloseCodes.getCloseCode(e.getClosingCode()), e.getMessage()));
            } catch (Exception wse) {
                webSocket.onClose(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, wse.getMessage()));
            }
        }
    }
}
