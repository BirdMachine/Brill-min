package com.birdie.brillbody;

import java.io.File;

public final class OpenAiProvider implements BrainProvider {
    private final OpenAiClient client;
    private final AppConfig config;

    public OpenAiProvider(AppConfig config) {
        this.config = config;
        this.client = new OpenAiClient(config.getBaseUrl(), config.getApiKey());
    }

    @Override
    public OpenAiClient.Transcription transcribe(File wavFile) throws Exception {
        return client.transcribe(wavFile, config.getTranscriptionModel());
    }

    @Override
    public OpenAiClient.BrainResponse respond(String userText, String previousResponseId) throws Exception {
        return client.respond(
                config.getModel(),
                config.getInstructions(),
                userText,
                previousResponseId,
                config.getIdentityId(),
                config.getBodyId());
    }
}
