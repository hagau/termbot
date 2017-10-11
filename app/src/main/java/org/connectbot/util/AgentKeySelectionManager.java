/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2017 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.util;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import org.connectbot.bean.AgentBean;
import org.connectbot.service.AgentManager;
import org.openintents.ssh.GetPublicKeyResponse;
import org.openintents.ssh.SSHAgentApi;
import org.openintents.ssh.utils.GetPublicKeyRequest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;


public class AgentKeySelectionManager implements AgentRequest.OnAgentResultCallback {
	public static final String AGENT_BEAN = "agent_bean";

	protected AgentManager agentManager = null;
	private ServiceConnection agentConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			agentManager = ((AgentManager.AgentBinder) service).getService();
			getKey(agentName);
		}

		public void onServiceDisconnected(ComponentName className) {
			agentManager = null;
		}
	};

	private Context appContext;
	private String agentName;
	private Handler updateHandler;

	public AgentKeySelectionManager(Context appContext, String agentName, Handler updateHandler) {
		this.appContext = appContext;
		this.agentName = agentName;
		this.updateHandler = updateHandler;
	}

    /**
	 * Select a key from an external ssh-agent
	 */
	public void selectKeyFromAgent() {
		Log.d(getClass().toString(), "====>>>> selectKeyFromAgent tid: "+ android.os.Process.myTid());

		appContext.bindService(new Intent(appContext, AgentManager.class), agentConnection, Context.BIND_AUTO_CREATE);

	}

	public void updateFragment(GetPublicKeyResponse response) {
		assert response != null; // response is never null anyway, so silence the warning
		int resultCode = response.getResultCode();

		Message message = updateHandler.obtainMessage(resultCode);

		if (resultCode == GetPublicKeyResponse.RESULT_CODE_SUCCESS) {
			Bundle bundle = new Bundle();

			byte[] encodedPublicKey = response.getEncodedPublicKey();
			int algorithm = response.getKeyAlgorithm();
			int format = response.getKeyFormat();

			// try decoding the encoded key to make sure it can be used for authentication later
			PublicKey publicKey = getPublicKey(encodedPublicKey, algorithm, format);
			if (publicKey == null) {
				message.what = GetPublicKeyResponse.RESULT_CODE_ERROR;
				message.sendToTarget();
				return;
			}

			AgentBean agentBean = new AgentBean();
			agentBean.setKeyIdentifier(response.getKeyID());
			try {
				agentBean.setKeyType(translateAlgorithm(algorithm));
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				message.what = GetPublicKeyResponse.RESULT_CODE_ERROR;
				message.sendToTarget();
				return;
			}
			agentBean.setPackageName(agentName);
			agentBean.setDescription(response.getKeyDescription());
			agentBean.setPublicKey(publicKey.getEncoded());

			bundle.putParcelable(AGENT_BEAN, agentBean);
			message.setData(bundle);
		}

		message.sendToTarget();
	}

	private PublicKey getPublicKey(byte[] encodedPublicKey, int algorithmFlag, int format) {
        PublicKey publicKey = null;
		if (format == SSHAgentApi.X509) {
			try {
				publicKey = PubkeyUtils.decodePublic(encodedPublicKey, translateAlgorithm(algorithmFlag));
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				return null;
			} catch (InvalidKeySpecException e) {
				e.printStackTrace();
				return null;
			}
		}
		return publicKey;
	}

	private String translateAlgorithm(int algorithm) throws NoSuchAlgorithmException {
		switch (algorithm) {
		case SSHAgentApi.RSA:
			return "RSA";
		case SSHAgentApi.DSA:
			return "DSA";
		case SSHAgentApi.ECDSA:
			return "EC";
		case SSHAgentApi.EDDSA:
			return "Ed25519";
		default:
			throw new NoSuchAlgorithmException("Algorithm not supported: "+ algorithm);
		}
	}

	private void getKey(String targetPackage) {

		Intent request = new GetPublicKeyRequest().toIntent();

		AgentRequest agentRequest = new AgentRequest(request, targetPackage);
		agentRequest.setAgentResultCallback(this);

		agentManager.execute(agentRequest);

    }

    public void onAgentResult(Intent data) {
		updateFragment(new GetPublicKeyResponse(data));
	}
}

