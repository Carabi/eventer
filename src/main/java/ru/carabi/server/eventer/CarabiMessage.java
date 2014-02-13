/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package ru.carabi.server.eventer;

import io.netty.channel.ChannelHandlerContext;

/**
 *
 * @author sasha
 */
public class CarabiMessage {
	public enum Type {
		reserved(0),
		ping(1),
		pong(2),
		auth(3),
		test(4),
		
		synch(10);
		private short code;
		Type (int code) {
			if (code != (short)code) {
				throw new IllegalArgumentException("Too big code: " + code);
			}
			this.code = (short)code;
		}
		
		public short getCode () {
			return code;
		}
	}
	
	private final Type type;
	private final String text;
	private final ChannelHandlerContext ctx;
	
	public CarabiMessage(String src, int type, ChannelHandlerContext ctx) {
		this.text = src;
		this.type = Type.values()[type];
		this.ctx = ctx;
	}
	
	public String getText() {
		return text;
	}
	
	public Type getType() {
		return type;
	}
}
