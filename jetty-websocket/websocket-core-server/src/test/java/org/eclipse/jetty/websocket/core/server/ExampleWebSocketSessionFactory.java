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

package org.eclipse.jetty.websocket.core.server;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;

class ExampleWebSocketSessionFactory implements WebSocketSessionFactory
{
    private final WebSocketPolicy defaultSessionPolicy;

    public ExampleWebSocketSessionFactory(WebSocketPolicy policy)
    {
        this.defaultSessionPolicy = policy.clonePolicy();
    }

    @Override
    public WebSocketPolicy getDefaultPolicy()
    {
        return this.defaultSessionPolicy;
    }

    @Override
    public List<ExtensionConfig> negotiate(Request request,
                                           Response response,
                                           List<String> offeredSubProtocols,
                                           List<ExtensionConfig> offeredExtensions)
    {
        // Example: Filter out a specific extension by name.
        List<ExtensionConfig> filteredExtensions = offeredExtensions.stream()
                .filter((config) -> !config.getName().equalsIgnoreCase("premessage-deflate"))
                .collect(Collectors.toList());

        return filteredExtensions;
    }

    @Override
    public WebSocketCoreSession newSession(Request request,
                                           Response response,
                                           WebSocketPolicy sessionPolicy,
                                           ExtensionStack extensionStack)
    {
        ExampleLocalEndpoint localEndpoint = new ExampleLocalEndpoint();
        ExampleRemoteEndpoint remoteEndpoint = new ExampleRemoteEndpoint(extensionStack);

        WebSocketCoreSession session =
                new WebSocketCoreSession(localEndpoint,remoteEndpoint,sessionPolicy,extensionStack);

        localEndpoint.setSession(session);
        return session;
    }

}
