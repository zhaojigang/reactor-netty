/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.server;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketServerOperations extends HttpServerOperations
		implements WebsocketInbound, WebsocketOutbound, BiConsumer<Void, Throwable> {

	final WebSocketServerHandshaker handshaker;
	final ChannelPromise            handshakerResult;

	volatile int closeSent;

	WebsocketServerOperations(String wsUrl,
			@Nullable String protocols,
			HttpServerOperations replaced) {
		super(replaced);

		Channel channel = replaced.channel();

		// Handshake
		WebSocketServerHandshakerFactory wsFactory =
				new WebSocketServerHandshakerFactory(wsUrl, protocols, true);
		handshaker = wsFactory.newHandshaker(replaced.nettyRequest);
		if (handshaker == null) {
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
			handshakerResult = null;
		}
		else {
			removeHandler(NettyPipeline.HttpServerHandler);

			handshakerResult = channel.newPromise();
			HttpRequest request = new DefaultFullHttpRequest(replaced.version(),
					replaced.method(),
					replaced.uri());

			request.headers()
			       .set(replaced.nettyRequest.headers());

			handshaker.handshake(channel,
					request,
					replaced.nettyResponse.headers()
					                      .remove(HttpHeaderNames.TRANSFER_ENCODING),
					handshakerResult)
			          .addListener(f -> markPersistent(false));
		}
	}

	@Override
	public HttpHeaders headers() {
		return requestHeaders();
	}

	@Override
	public void onInboundNext(ChannelHandlerContext ctx, Object frame) {
		if (frame instanceof CloseWebSocketFrame && ((CloseWebSocketFrame) frame).isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug("CloseWebSocketFrame detected. Closing Websocket");
			}
			onInboundComplete();
			CloseWebSocketFrame close = (CloseWebSocketFrame) frame;
			sendCloseNow(new CloseWebSocketFrame(true,
					close.rsv(),
					close.content()), f -> onHandlerTerminate());
			return;
		}
		if (frame instanceof PingWebSocketFrame) {
			ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) frame).content()));
			ctx.read();
			return;
		}
		if (frame != LastHttpContent.EMPTY_LAST_CONTENT) {
			super.onInboundNext(ctx, frame);
		}
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	public void accept(Void aVoid, Throwable throwable) {
		if (throwable == null) {
			if (channel().isActive()) {
				sendCloseNow(null, f -> onHandlerTerminate());
			}
		}
		else {
			onOutboundError(throwable);
		}
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			sendCloseNow(new CloseWebSocketFrame(1002, "Server internal error"), f ->
					onHandlerTerminate());
		}
	}

	@Override
	public Mono<Void> sendClose() {
		return sendClose(new CloseWebSocketFrame());
	}

	@Override
	public Mono<Void> sendClose(int rsv) {
		return sendClose(new CloseWebSocketFrame(true, rsv));
	}

	@Override
	public Mono<Void> sendClose(int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(statusCode, reasonText));
	}

	@Override
	public Mono<Void> sendClose(int rsv, int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(true, rsv, statusCode, reasonText));
	}

	Mono<Void> sendClose(CloseWebSocketFrame frame) {
		if (CLOSE_SENT.get(this) == 0) {
			return FutureMono.deferFuture(() -> {
				if (CLOSE_SENT.getAndSet(this, 1) == 0) {
					return channel().writeAndFlush(frame)
					                .addListener(ChannelFutureListener.CLOSE);
				}
				return channel().newSucceededFuture();
			});
		}
		return Mono.empty();
	}

	void sendCloseNow(@Nullable CloseWebSocketFrame frame, ChannelFutureListener listener) {
		if (frame != null && !frame.isFinalFragment()) {
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			ChannelFuture f = channel().writeAndFlush(
					frame == null ? new CloseWebSocketFrame() : frame);
			f.addListener(listener);
		}
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	public String selectedSubprotocol() {
		return handshaker.selectedSubprotocol();
	}

	static final AtomicIntegerFieldUpdater<WebsocketServerOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketServerOperations.class,
					"closeSent");
}