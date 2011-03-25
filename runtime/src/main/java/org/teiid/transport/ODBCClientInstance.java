/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.transport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.teiid.core.util.ReflectionHelper;
import org.teiid.jdbc.TeiidDriver;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.logging.MessageLevel;
import org.teiid.net.CommunicationException;
import org.teiid.net.socket.ObjectChannel;
import org.teiid.net.socket.ServiceInvocationStruct;
import org.teiid.odbc.ODBCClientRemote;
import org.teiid.odbc.ODBCServerRemote;
import org.teiid.odbc.ODBCServerRemoteImpl;
import org.teiid.transport.PgFrontendProtocol.PGRequest;

public class ODBCClientInstance implements ChannelListener{

	private ODBCClientRemote client;
	private ODBCServerRemoteImpl server;
	private ReflectionHelper serverProxy = new ReflectionHelper(ODBCServerRemote.class);
	private boolean hasPending;
	
	public ODBCClientInstance(final ObjectChannel channel, ODBCServerRemote.AuthenticationType authType, TeiidDriver driver) {
		this.client = (ODBCClientRemote)Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] {ODBCClientRemote.class}, new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				if (LogManager.isMessageToBeRecorded(LogConstants.CTX_ODBC, MessageLevel.TRACE)) {
					LogManager.logTrace(LogConstants.CTX_ODBC, "invoking client method:", method.getName(), Arrays.deepToString(args)); //$NON-NLS-1$
				}
				ServiceInvocationStruct message = new ServiceInvocationStruct(args, method.getName(),ODBCServerRemote.class);
				channel.write(message);
				return null;
			}
		});
		this.server = new ODBCServerRemoteImpl(this, authType, driver);
	}
	
	public ODBCClientRemote getClient() {
		return client;
	}
	
	@Override
	public void disconnected() {
		server.terminate();
	}

	@Override
	public void exceptionOccurred(Throwable t) {
		server.terminate();
	}
	
	public boolean hasPending() {
		return hasPending;
	}

	@Override
	public void onConnection() throws CommunicationException {
	}

	@Override
	public void receivedMessage(Object msg) throws CommunicationException {
        if (msg instanceof PGRequest) {
        	PGRequest request = (PGRequest)msg;
        	hasPending = request.hasPending;
            processMessage(request.struct);
        }
	}

	private void processMessage(ServiceInvocationStruct serviceStruct) {
		try {
			Method m = this.serverProxy.findBestMethodOnTarget(serviceStruct.methodName, serviceStruct.args);
			try {
				// since the postgres protocol can produce more than single response
				// objects to a request, all the methods are designed to return void.
				// and relies on client interface to build the responses.
				m.invoke(this.server, serviceStruct.args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}		
		} catch (Throwable e) {
			this.client.errorOccurred(e);
		}
	}

}
