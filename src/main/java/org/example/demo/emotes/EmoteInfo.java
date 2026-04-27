package org.example.demo.emotes;

import java.nio.file.Path;

public record EmoteInfo(
        String id,
        String name,
        Path localPath,
        String sourceUrl,
        EmoteProvider provider
) {
}