package com.br.disknode.server.handler.impl;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.br.disknode.DiskWriterManager;
import com.br.disknode.server.handler.DiskMessage;
import com.br.disknode.server.handler.HandleResult;
import com.br.disknode.server.handler.HandleResultCallback;
import com.br.disknode.server.netty.MessageHandler;

public class OpenMessageHandler implements MessageHandler<DiskMessage> {
	private static final Logger LOG = LoggerFactory.getLogger(OpenMessageHandler.class);
	
	public static final String PARAM_OVERRIDE = "override";
	
	private DiskWriterManager nodeManager;
	
	public OpenMessageHandler(DiskWriterManager nodeManager) {
		this.nodeManager = nodeManager;
	}

	@Override
	public void handle(DiskMessage msg, HandleResultCallback callback) {
		HandleResult result = new HandleResult();
		
		String overrideParam = msg.getParams().get(PARAM_OVERRIDE);
		boolean override = overrideParam == null ? false : true;
		
		try {
			LOG.debug("OPEN [{}] override[{}]", msg.getFilePath(), override);
			nodeManager.buildDiskWriter(msg.getFilePath(), override);
			result.setSuccess(true);
		} catch (IOException e) {
			result.setSuccess(false);
			result.setCause(e);
		} finally {
			callback.completed(result);
		}
	}

}