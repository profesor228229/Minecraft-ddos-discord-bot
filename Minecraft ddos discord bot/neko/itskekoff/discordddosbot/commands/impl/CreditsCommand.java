package neko.itskekoff.discordddosbot.commands.impl;

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import neko.itskekoff.discordddosbot.commands.SlashCommand;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class CreditsCommand implements SlashCommand {
    @Override
    public String getname() {
        return "credits";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent e) {
        EmbedCreateSpec emb = EmbedCreateSpec.builder()
                .color(Color.CINNABAR)
                .addField("Создатель", "itskekoff", true)
                .addField("На чем бот", "Discord4j | JAVA 11", true)
                .addField("Список методов", "/methods", true)
                .timestamp(Instant.now())
                .footer("By グ影 ッⅫッSkillッⅫッ🔱影牡 with cup of Coffee and love :3", "https://i.ytimg.com/vi/HJ9Hq5wu-xw/maxresdefault.jpg")
                .build();
        return e.reply()
                .withEmbeds(emb);

    }
}
