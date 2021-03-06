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

package org.kaazing.gateway.server.util.io;

import java.util.concurrent.Executor;
import org.apache.mina.core.future.IoFuture;
import org.kaazing.mina.core.service.AbstractIoServiceEx;
import org.kaazing.mina.core.session.AbstractIoSessionConfigEx;
import org.kaazing.mina.core.session.IoSessionConfigEx;

public abstract class IoServiceAdapterEx extends AbstractIoServiceEx {

    public IoServiceAdapterEx() {
        super(new AbstractIoSessionConfigEx() {
                  @Override
                  protected void doSetAll(IoSessionConfigEx config) {
                  }
              },
                new Executor() {
                    @Override
                    public void execute(Runnable command) {
                    }
                });
    }

    @Override
    protected IoFuture dispose0() throws Exception {
        return null;
    }

}
