//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.server.handshake;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.server.AcceptHash;
import org.eclipse.jetty.websocket.core.server.ContextConnectorConfiguration;
import org.eclipse.jetty.websocket.core.server.OpeningHandshake;
import org.eclipse.jetty.websocket.core.server.WebSocketConnectionFactory;
import org.eclipse.jetty.websocket.core.server.WebSocketSessionFactory;

/**
 * This is the Opening Handshake processing for RFC6455 (HTTP/1.1 expectations)
 */
public final class RFC6455OpeningHandshake implements OpeningHandshake
{
    static final Logger LOG = Log.getLogger(RFC6455OpeningHandshake.class);
    private static HttpField UpgradeWebSocket = new PreEncodedHttpField(HttpHeader.UPGRADE, "WebSocket");
    private static HttpField ConnectionUpgrade = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeader.UPGRADE.asString());

    public final static int VERSION = WebSocketConstants.SPEC_VERSION;

    public boolean upgrade(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        Request baseRequest = Request.getBaseRequest(request);

        // Basic Upgrade Requirements
        if (!HttpMethod.GET.is(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not method GET {}", baseRequest);
            return false;
        }

        if (!HttpVersion.HTTP_1_1.equals(baseRequest.getHttpVersion()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not required HTTP/1.1 {}", baseRequest);
            return false;
        }

        // Collecting relevant request headers.
        ServletContext context = baseRequest.getServletContext();
        HttpChannel channel = baseRequest.getHttpChannel();
        Connector connector = channel.getConnector();

        boolean upgrade = false;
        QuotedCSV connectionCSVs = null;
        String key = null;
        QuotedCSV extensionCSVs = null;
        QuotedCSV subprotocolCSVs = null;

        for (HttpField field : baseRequest.getHttpFields())
        {
            if (field.getHeader() != null)
            {
                switch (field.getHeader())
                {
                    case UPGRADE:
                        if (!"websocket".equalsIgnoreCase(field.getValue()))
                            return false;
                        upgrade = true;
                        break;

                    case CONNECTION:
                        if (connectionCSVs == null)
                            connectionCSVs = new QuotedCSV();
                        connectionCSVs.addValue(field.getValue().toLowerCase());
                        break;

                    case SEC_WEBSOCKET_KEY:
                        key = field.getValue();
                        break;

                    case SEC_WEBSOCKET_EXTENSIONS:
                        if (extensionCSVs == null)
                            extensionCSVs = new QuotedCSV();
                        extensionCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_SUBPROTOCOL:
                        if (subprotocolCSVs == null)
                            subprotocolCSVs = new QuotedCSV();
                        subprotocolCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_VERSION:
                        if (field.getIntValue() != VERSION)
                            return false;
                        break;

                    default:
                }
            }
        }

        if (!upgrade)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Missing 'Upgrade: websocket' header {}", baseRequest);
            return false;
        }

        if (connectionCSVs == null || !connectionCSVs.getValues().contains("upgrade"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Missing 'Connection: upgrade' header {}", baseRequest);
            return false;
        }

        if (key == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Missing 'Sec-WebSocket-Key` header {}", baseRequest);
            return false;
        }

        // Establish classes needed for negotiation

        // TODO: this needs a better home, perhaps a container level location
        WebSocketExtensionRegistry extensionRegistry = (WebSocketExtensionRegistry) context.getAttribute(WebSocketExtensionRegistry.class.getName());
        if (extensionRegistry == null)
        {
            extensionRegistry = new WebSocketExtensionRegistry();
        }

        ContextHandler contextHandler = ContextHandler.getContextHandler(context);
        DecoratedObjectFactory objectFactory = null;
        if (contextHandler instanceof ServletContextHandler)
        {
            objectFactory = ((ServletContextHandler) contextHandler).getObjectFactory();
        }
        if (objectFactory == null)
        {
            // TODO: this needs a better home, perhaps a container level location
            objectFactory = new DecoratedObjectFactory();
        }

        WebSocketConnectionFactory connectionFactory = ContextConnectorConfiguration
                .lookup(WebSocketConnectionFactory.class, context, connector);
        if (connectionFactory == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection factory {}", baseRequest);
            return false;
        }

        WebSocketSessionFactory sessionFactory = ContextConnectorConfiguration
                .lookup(WebSocketSessionFactory.class, context, connector);
        if (sessionFactory == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no session factory {}", baseRequest);
            return false;
        }

        Response baseResponse = baseRequest.getResponse();

        // Collect / prepare data from request for negotiation

        List<String> offeredSubProtocols = subprotocolCSVs == null
                ? Collections.emptyList()
                : subprotocolCSVs.getValues();

        List<ExtensionConfig> offeredExtensions = extensionCSVs == null
                ? Collections.emptyList()
                : extensionCSVs.getValues().stream().map(ExtensionConfig::parse).collect(Collectors.toList());

        // Perform session specific negotiation

        List<ExtensionConfig> wantedExtensions = sessionFactory.negotiate(baseRequest, baseResponse,
                offeredSubProtocols, offeredExtensions);

        // Create new Session from negotiated handshake

        WebSocketPolicy sessionPolicy = sessionFactory.getDefaultPolicy().clonePolicy();
        ByteBufferPool bufferPool = connectionFactory.getBufferPool();

        // Perform Default extension stack negotiation

        ExtensionStack extensionStack = new ExtensionStack(extensionRegistry);
        extensionStack.negotiate(objectFactory, sessionPolicy, bufferPool, wantedExtensions);

        // Perform (optional) Default subprotocol negotiation

        String selectedSubProtocol = null;
        // Did negotiate set it?
        if ( (offeredSubProtocols.size() > 0) && (baseResponse.getHttpFields().get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL) == null) )
        {
            // pick first one
            selectedSubProtocol = offeredSubProtocols.get(0);
        }

        // Create new session

        WebSocketCoreSession session = sessionFactory
                .newSession(baseRequest,
                        baseResponse,
                        sessionPolicy,
                        extensionStack);

        if (session == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no session {}", baseRequest);
            return false;
        }

        if (session.getPolicy() == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no policy {}", baseRequest);
            return false;
        }

        // Create a connection

        WebSocketCoreConnection connection = connectionFactory.newConnection(connector, channel.getEndPoint(), session);
        if (connection == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection {}", baseRequest);
        }

        session.setWebSocketConnection(connection);

        // Send upgrade response

        baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        baseResponse.getHttpFields().add(UpgradeWebSocket);
        baseResponse.getHttpFields().add(ConnectionUpgrade);
        baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(key));
        baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,
                ExtensionConfig.toHeaderValue(session.getExtensionStack().getNegotiatedExtensions()));

        if (selectedSubProtocol != null)
            baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, selectedSubProtocol);

        baseResponse.flushBuffer();
        baseRequest.setHandled(true);

        // Perform internal jetty connection upgrade

        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={}", connection, session);

        baseRequest.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        return true;
    }
}
