/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat, Inc. and/or its affiliates, and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.server.memcached.commands;

import static org.infinispan.server.memcached.Reply.VERSION;

import static org.infinispan.server.memcached.TextProtocolUtil.CRLF;

import org.infinispan.Version;
import org.infinispan.server.core.transport.Channel;
import org.infinispan.server.core.transport.ChannelBuffers;
import org.infinispan.server.core.transport.ChannelHandlerContext;
import org.infinispan.server.memcached.interceptors.TextProtocolVisitor;


/**
 * VersionCommand.
 * 
 * @author Galder Zamarreño
 * @since 4.0
 */
public enum VersionCommand implements TextCommand {
   INSTANCE;

   @Override
   public Object acceptVisitor(ChannelHandlerContext ctx, TextProtocolVisitor next) throws Throwable {
      return next.visitVersion(ctx, this);
   }

   @Override
   public CommandType getType() {
      return CommandType.VERSION;
   }

   @Override
   public Object perform(ChannelHandlerContext ctx) throws Exception {
      Channel ch = ctx.getChannel();
      String version = ' ' + Version.version;
      ChannelBuffers buffers = ctx.getChannelBuffers();
      ch.write(buffers.wrappedBuffer(buffers.wrappedBuffer(VERSION.bytes()), buffers.wrappedBuffer(version.getBytes()), buffers.wrappedBuffer(CRLF)));
      return VERSION;
   }

   public static VersionCommand newVersionCommand() {
      return INSTANCE;
   }
}
