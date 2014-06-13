//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.api.server;

import java.util.Map;

import org.eclipse.jetty.http2.api.Session;

public interface ServerSessionListener extends Session.Listener
{
    public void onConnect(Session session);

    public Map<Integer,Integer> onPreface(Session session);

    public static class Adapter extends Session.Listener.Adapter implements ServerSessionListener
    {
        @Override
        public void onConnect(Session session)
        {
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            return null;
        }
    }
}
