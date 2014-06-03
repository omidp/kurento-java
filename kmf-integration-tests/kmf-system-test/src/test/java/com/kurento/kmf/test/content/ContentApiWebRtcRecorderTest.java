/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package com.kurento.kmf.test.content;

import java.awt.Color;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import com.kurento.kmf.content.HttpPlayerHandler;
import com.kurento.kmf.content.HttpPlayerService;
import com.kurento.kmf.content.HttpPlayerSession;
import com.kurento.kmf.content.WebRtcContentHandler;
import com.kurento.kmf.content.WebRtcContentService;
import com.kurento.kmf.content.WebRtcContentSession;
import com.kurento.kmf.media.HttpGetEndpoint;
import com.kurento.kmf.media.MediaPipeline;
import com.kurento.kmf.media.PlayerEndpoint;
import com.kurento.kmf.media.RecorderEndpoint;
import com.kurento.kmf.media.WebRtcEndpoint;
import com.kurento.kmf.test.base.ContentApiTest;
import com.kurento.kmf.test.client.Browser;
import com.kurento.kmf.test.client.BrowserClient;
import com.kurento.kmf.test.client.Client;
import com.kurento.kmf.test.mediainfo.MediaInfo;

/**
 * <strong>Description</strong>: Record video on WebRTC: Test KMS is able to
 * record video received on WebRTC<br/>
 * <strong>Pipeline</strong>: WebRtcEndpoint -> RecorderEndpoint<br/>
 * <strong>Pass criteria</strong>: <br/>
 * <ul>
 * <li>Browser #1 and #2 starts before 60 seconds (default timeout)</li>
 * <li>Video/audio codecs of the recording are correct (VP8/Vorbis)</li>
 * <li>Record play time does not differ in a 10% of the transmitted video</li>
 * <li>Browser #1 and #2 stops before 60 seconds (default timeout)</li>
 * </ul>
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 4.2.3
 */
public class ContentApiWebRtcRecorderTest extends ContentApiTest {

	private static final String HANDLER1 = "/webrtcRecorder";
	private static final String HANDLER2 = "/webrtcRecorderPlayer";
	private static final String FILE_SCHEMA = "file://";
	private static final String RECORDING = "/tmp/webrtc";

	private static int PLAYTIME = 5; // seconds

	@WebRtcContentService(path = HANDLER1)
	public static class WebRtcHandler extends WebRtcContentHandler {

		private RecorderEndpoint recorderEndPoint;

		@Override
		public synchronized void onContentRequest(
				WebRtcContentSession contentSession) throws Exception {
			MediaPipeline mp = contentSession.getMediaPipelineFactory()
					.create();
			contentSession.releaseOnTerminate(mp);
			WebRtcEndpoint webRtcEndpoint = mp.newWebRtcEndpoint().build();
			recorderEndPoint = mp.newRecorderEndpoint(FILE_SCHEMA + RECORDING)
					.build();
			webRtcEndpoint.connect(webRtcEndpoint);
			webRtcEndpoint.connect(recorderEndPoint);
			contentSession.start(webRtcEndpoint);

			terminateLatch = new CountDownLatch(1);
		}

		@Override
		public void onContentStarted(WebRtcContentSession contentSession) {
			recorderEndPoint.record();
		}

		@Override
		public void onSessionTerminated(WebRtcContentSession contentSession,
				int code, String reason) throws Exception {
			recorderEndPoint.stop();
			// recorderEndPoint.release();

			terminateLatch.countDown();
		}
	}

	@HttpPlayerService(path = HANDLER2, redirect = false, useControlProtocol = false)
	public static class Player extends HttpPlayerHandler {

		private PlayerEndpoint playerEP;

		@Override
		public void onContentRequest(HttpPlayerSession session)
				throws Exception {
			MediaPipeline mp = session.getMediaPipelineFactory().create();
			playerEP = mp.newPlayerEndpoint(FILE_SCHEMA + RECORDING).build();
			HttpGetEndpoint httpEP = mp.newHttpGetEndpoint().terminateOnEOS()
					.build();
			playerEP.connect(httpEP);
			session.start(httpEP);

			terminateLatch = new CountDownLatch(1);
		}

		@Override
		public void onContentStarted(HttpPlayerSession session)
				throws Exception {
			playerEP.play();
		}

		@Override
		public void onSessionTerminated(HttpPlayerSession session, int code,
				String reason) throws Exception {
			super.onSessionTerminated(session, code, reason);
			terminateLatch.countDown();
		}
	}

	@Test
	public void testWebRtcRecorder() throws InterruptedException {
		// Step 1: Record video from WebRTC in loopback
		try (BrowserClient browser = new BrowserClient.Builder()
				.browser(Browser.CHROME).client(Client.WEBRTC).build()) {
			browser.setURL(HANDLER1);
			browser.subscribeEvents("playing");
			browser.start();

			// Assertions
			Assert.assertTrue("Timeout waiting playing event",
					browser.waitForEvent("playing"));

			// Recording time
			Thread.sleep(PLAYTIME * 1000);

			// Ending session in order
			browser.stop();
			Assert.assertTrue(
					"Timeout waiting onSessionTerminated",
					terminateLatch.await(browser.getTimeout(), TimeUnit.SECONDS));
		}

		// Step 2: Assess video/audio codec of the recorder video
		MediaInfo info = new MediaInfo();
		info.open(new File(RECORDING));
		String videoFormat = info.get(MediaInfo.StreamKind.Video, 0, "Format",
				MediaInfo.InfoKind.Text, MediaInfo.InfoKind.Name);
		String audioFormat = info.get(MediaInfo.StreamKind.Audio, 0, "Format",
				MediaInfo.InfoKind.Text, MediaInfo.InfoKind.Name);
		final String expectedVideoCodec = "VP8";
		final String expectedAudioCodec = "Vorbis";
		info.close();

		Assert.assertEquals("Expectec video codec is " + expectedVideoCodec
				+ " and the recorded video is " + videoFormat,
				expectedVideoCodec, videoFormat);
		Assert.assertEquals("Expectec audio codec is " + expectedAudioCodec
				+ " and the recorded video is " + audioFormat,
				expectedAudioCodec, audioFormat);

		// Step 3: Play recorded video to assess the video duration
		try (BrowserClient browser = new BrowserClient.Builder()
				.browser(Browser.CHROME).client(Client.PLAYER).build()) {
			browser.setURL(HANDLER2);
			browser.subscribeEvents("playing", "ended");
			browser.start();

			// Assertions
			Assert.assertTrue("Timeout waiting playing event",
					browser.waitForEvent("playing"));
			Assert.assertTrue("Timeout waiting ended event",
					browser.waitForEvent("ended"));
			Assert.assertTrue("Play time must be around " + PLAYTIME
					+ " seconds", compare(PLAYTIME, browser.getCurrentTime()));
			Assert.assertTrue(
					"The color of the video should be green (RGB #008700)",
					browser.colorSimilarTo(new Color(0, 135, 0)));

			// Ending session in order
			browser.stop();
			Assert.assertTrue(
					"Timeout waiting onSessionTerminated",
					terminateLatch.await(browser.getTimeout(), TimeUnit.SECONDS));
		}
	}
}
