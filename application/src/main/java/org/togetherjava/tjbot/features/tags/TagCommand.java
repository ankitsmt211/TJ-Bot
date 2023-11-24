package org.togetherjava.tjbot.features.tags;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import org.togetherjava.tjbot.config.Config;
import org.togetherjava.tjbot.features.CommandVisibility;
import org.togetherjava.tjbot.features.SlashCommandAdapter;
import org.togetherjava.tjbot.features.utils.LinkPreview;
import org.togetherjava.tjbot.features.utils.LinkPreviews;
import org.togetherjava.tjbot.features.utils.StringDistances;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Implements the {@code /tag} command which lets the bot respond content of a tag that has been
 * added previously.
 * <p>
 * Tags can be added by using {@link TagManageCommand} and a list of all tags is available using
 * {@link TagsCommand}.
 */
public final class TagCommand extends SlashCommandAdapter {
    private final TagSystem tagSystem;
    private static final int MAX_SUGGESTIONS = 5;
    static final String ID_OPTION = "id";
    static final String REPLY_TO_USER_OPTION = "reply-to";
    private final Predicate<String> isHelpForum;
    private final Predicate<String> isBotsChannel;
    private final Predicate<String> hasTagManageRole;

    /**
     * Creates a new instance, using the given tag system as base.
     *
     * @param tagSystem the system providing the actual tag data
     */
    public TagCommand(TagSystem tagSystem, Config config) {
        super("tag", "Display a tags content", CommandVisibility.GUILD);

        this.tagSystem = tagSystem;

        getData().addOptions(
                new OptionData(OptionType.STRING, ID_OPTION, "The id of the tag to display", true,
                        true),
                new OptionData(OptionType.USER, REPLY_TO_USER_OPTION,
                        "Optionally, the user who you want to reply to", false));

        this.isHelpForum =
                Pattern.compile(config.getHelpSystem().getHelpForumPattern()).asMatchPredicate();
        this.isBotsChannel = Pattern.compile(config.getBotsChannelPattern()).asMatchPredicate();
        this.hasTagManageRole =
                Pattern.compile(config.getTagManageRolePattern()).asMatchPredicate();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        AutoCompleteQuery focusedOption = event.getFocusedOption();

        if (!focusedOption.getName().equals(ID_OPTION)) {
            throw new IllegalArgumentException(
                    "Unexpected option, was: " + focusedOption.getName());
        }

        Collection<Command.Choice> choices = StringDistances
            .closeMatches(focusedOption.getValue(), tagSystem.getAllIds(), MAX_SUGGESTIONS)
            .stream()
            .map(id -> new Command.Choice(id, id))
            .toList();

        event.replyChoices(choices).queue();
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event) {
        String id = event.getOption(ID_OPTION).getAsString();
        OptionMapping replyToUserOption = event.getOption(REPLY_TO_USER_OPTION);

        if (!isTriggeredInAllowedChannels(event) && (!hasPerms(event))) {
            TextChannel botsCommandChannel = getBotsCommandChannel(event.getJDA());

            event.reply(
                    "Command can only be used in %s channel or help forum, avoid spamming helper forum with usage."
                        .formatted(botsCommandChannel))
                .setEphemeral(true)
                .queue();
            return;
        }

        if (tagSystem.handleIsUnknownTag(id, event)) {
            return;
        }

        String tagContent = tagSystem.getTag(id).orElseThrow();
        String tagsCommandNote = "You can use /tags in any channel now";

        MessageEmbed contentEmbed = new EmbedBuilder().setDescription(tagContent)
            .setFooter(tagsCommandNote)
            .setTimestamp(Instant.now())
            .setColor(TagSystem.AMBIENT_COLOR)
            .build();

        Optional<String> replyToUserMention = Optional.ofNullable(replyToUserOption)
            .map(OptionMapping::getAsUser)
            .map(User::getAsMention);

        List<String> links = LinkPreviews.extractLinks(tagContent)
            .stream()
            .limit(Message.MAX_EMBED_COUNT - 1L)
            .toList();
        if (links.isEmpty()) {
            // No link previews
            ReplyCallbackAction message = event.replyEmbeds(contentEmbed);
            replyToUserMention.ifPresent(message::setContent);
            message.queue();
            return;
        }

        event.deferReply().queue();

        respondWithLinkPreviews(event.getHook(), links, contentEmbed, replyToUserMention);
    }

    private void respondWithLinkPreviews(InteractionHook eventHook, List<String> links,
            MessageEmbed contentEmbed, Optional<String> replyToUserMention) {
        LinkPreviews.createLinkPreviews(links).thenAccept(linkPreviews -> {
            if (linkPreviews.isEmpty()) {
                // Did not find any previews
                MessageEditBuilder message = new MessageEditBuilder().setEmbeds(contentEmbed);
                replyToUserMention.ifPresent(message::setContent);
                eventHook.editOriginal(message.build()).queue();
                return;
            }

            Collection<MessageEmbed> embeds = new ArrayList<>();
            embeds.add(contentEmbed);
            embeds.addAll(linkPreviews.stream().map(LinkPreview::embed).toList());

            List<FileUpload> attachments = linkPreviews.stream()
                .map(LinkPreview::attachment)
                .filter(Objects::nonNull)
                .toList();

            MessageEditBuilder message =
                    new MessageEditBuilder().setEmbeds(embeds).setFiles(attachments);
            replyToUserMention.ifPresent(message::setContent);
            eventHook.editOriginal(message.build()).queue();
        });
    }

    private boolean isTriggeredInAllowedChannels(SlashCommandInteractionEvent event) {
        if (!event.getChannel().getType().isThread()) {
            return isBotsChannel.test(event.getChannel().getName());
        } else {
            ThreadChannel threadChannel = event.getChannel().asThreadChannel();
            String rootChannelName = threadChannel.getParentChannel().getName();
            return isHelpForum.test(rootChannelName);
        }
    }

    private boolean hasPerms(SlashCommandInteractionEvent event) {
        return event.getMember().getRoles().stream().map(Role::getName).anyMatch(hasTagManageRole);
    }

    private TextChannel getBotsCommandChannel(JDA jda) {
        Optional<TextChannel> botsChannelOptional = jda.getTextChannels()
            .stream()
            .filter(channel -> isBotsChannel.test(channel.getName()))
            .findFirst();
        return botsChannelOptional.orElseThrow(() -> new IllegalArgumentException(
                "Unable to get channel used for bots command, try fixing config"));
    }
}
