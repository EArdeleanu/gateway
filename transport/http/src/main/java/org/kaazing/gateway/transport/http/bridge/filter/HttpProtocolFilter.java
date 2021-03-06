/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.transport.http.bridge.filter;

import static java.lang.System.currentTimeMillis;
import static org.kaazing.gateway.resource.address.http.HttpInjectableHeader.DATE;
import static org.kaazing.gateway.resource.address.http.HttpInjectableHeader.SERVER;
import static org.kaazing.gateway.transport.http.HttpUtils.formatDateHeader;

import java.util.Set;

import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.kaazing.gateway.resource.address.http.HttpInjectableHeader;
import org.kaazing.gateway.transport.http.HttpAcceptProcessor;
import org.kaazing.gateway.transport.http.HttpHeaders;
import org.kaazing.gateway.transport.http.bridge.HttpResponseMessage;

public class HttpProtocolFilter extends HttpFilterAdapter {

    public static final String PROTOCOL_HTTP_1_1 = "http/1.1";

    @Override
    protected void filterWriteHttpResponse(NextFilter nextFilter, IoSession session, WriteRequest writeRequest,
            HttpResponseMessage httpResponse) throws Exception {
        // GL.debug("http", getClass().getSimpleName() + " response write.");

        Set<HttpInjectableHeader> injectableHeaders = httpResponse.getInjectableHeaders();

        if (injectableHeaders.contains(SERVER)) {
            HttpAcceptProcessor.setServerHeader(session, httpResponse);
        }

        if (injectableHeaders.contains(DATE) && !httpResponse.hasHeader(HttpHeaders.HEADER_DATE)) {
            httpResponse.setHeader(HttpHeaders.HEADER_DATE, formatDateHeader(currentTimeMillis()));
        }
        
        super.filterWriteHttpResponse(nextFilter, session, writeRequest, httpResponse);
    }
}
