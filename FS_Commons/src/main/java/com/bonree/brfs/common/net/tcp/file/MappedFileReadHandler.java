package com.bonree.brfs.common.net.tcp.file;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.utils.BufferUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.io.Files;
import com.google.common.primitives.Ints;

@Sharable
public class MappedFileReadHandler extends SimpleChannelInboundHandler<ReadObject> {
	private static final Logger LOG = LoggerFactory.getLogger(MappedFileReadHandler.class);
	
	private ReadObjectTranslator translator;
	private LoadingCache<String, MappedByteBuffer> bufferCache = (LoadingCache<String, MappedByteBuffer>) CacheBuilder.newBuilder()
			.concurrencyLevel(Runtime.getRuntime().availableProcessors())
			.maximumSize(20)
			.initialCapacity(10)
			.expireAfterAccess(30, TimeUnit.SECONDS)
			.removalListener(new RemovalListener<String, MappedByteBuffer>() {

				@Override
				public void onRemoval(RemovalNotification<String, MappedByteBuffer> notification) {
					BufferUtils.release(notification.getValue());
				}
			})
			.build(new CacheLoader<String, MappedByteBuffer>() {

				@Override
				public MappedByteBuffer load(String filePath) throws Exception {
					return Files.map(new File(filePath), MapMode.READ_ONLY);
				}
				
			});
	
	public MappedFileReadHandler(ReadObjectTranslator translator) {
		this.translator = translator;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, ReadObject readObject)throws Exception {
		String filePath = (readObject.getRaw() & ReadObject.RAW_PATH) == 0 ?
				translator.filePath(readObject.getFilePath()) : readObject.getFilePath();
		
		MappedByteBuffer fileBuffer = null;
		try {
			fileBuffer = bufferCache.get(filePath);
			
			long readOffset = (readObject.getRaw() & ReadObject.RAW_OFFSET) == 0 ? translator.offset(readObject.getOffset()) : readObject.getOffset();
			int readLength = (readObject.getRaw() & ReadObject.RAW_LENGTH) == 0 ? translator.length(readObject.getLength()) : readObject.getLength();
			long fileLength = fileBuffer.capacity();
			if(readOffset < 0 || readOffset > fileLength) {
				LOG.error("unexcepted file offset : {}", readOffset);
				ctx.writeAndFlush(Unpooled.wrappedBuffer(Ints.toByteArray(readObject.getToken()), Ints.toByteArray(-1)))
				.addListener(ChannelFutureListener.CLOSE);
				return;
			}
			
			int readableLength = (int) Math.min(readLength, fileLength - readOffset);
			
			ByteBuffer contentBuffer = fileBuffer.slice();
			contentBuffer.position((int) readOffset);
			contentBuffer.limit((int) (readOffset + readableLength));
			
	        ctx.writeAndFlush(Unpooled.wrappedBuffer(ByteBuffer.wrap(Ints.toByteArray(readObject.getToken())),
	        		ByteBuffer.wrap(Ints.toByteArray(readableLength)),
	        		contentBuffer.slice())).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
		} catch (ExecutionException e) {
			LOG.error("can not open file channel for {}", filePath, e);
			ctx.writeAndFlush(Unpooled.wrappedBuffer(Ints.toByteArray(readObject.getToken()), Ints.toByteArray(-1)))
			.addListener(ChannelFutureListener.CLOSE);
			return;
		} catch (Exception e) {
			LOG.error("read file error", e);
			ctx.writeAndFlush(Unpooled.wrappedBuffer(Ints.toByteArray(readObject.getToken()), Ints.toByteArray(-1)))
			.addListener(ChannelFutureListener.CLOSE);
			return;
		} finally {
			bufferCache.cleanUp();
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		LOG.error("file read error", cause);
		ctx.close();
	}

}