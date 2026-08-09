package com.birdie.brillbody;

import java.io.File;

/** Provider seam reserved for OpenAI, LAN-hosted OpenAI-compatible servers, and full Brill later. */
public interface BrainProvider {
    OpenAiClient.Transcription transcribe(File wavFile) throws Exception;
    OpenAiClient.BrainResponse respond(String userText, String previousResponseId) throws Exception;
}
