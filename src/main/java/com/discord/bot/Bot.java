package com.discord.bot;

import com.clickntap.vimeo.Vimeo;
import com.clickntap.vimeo.VimeoException;
import com.discord.bot.Entity.Post;
import com.discord.bot.Event.AdminCommands;
import com.discord.bot.Event.RedditCommands;
import com.discord.bot.Event.TextCommands;
import com.discord.bot.Service.PostService;
import net.dean.jraw.RedditClient;
import net.dean.jraw.http.NetworkAdapter;
import net.dean.jraw.http.OkHttpNetworkAdapter;
import net.dean.jraw.http.UserAgent;
import net.dean.jraw.models.Listing;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.SubredditSort;
import net.dean.jraw.models.TimePeriod;
import net.dean.jraw.oauth.Credentials;
import net.dean.jraw.oauth.OAuthHelper;
import net.dean.jraw.pagination.DefaultPaginator;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;

@Configuration
@EnableScheduling
public class Bot {

    @Value("${reddit_username}")
    private String REDDIT_USERNAME;
    @Value("${reddit_password}")
    private String REDDIT_PASSWORD;
    @Value("${reddit_client_id}")
    private String REDDIT_CLIENT_ID;
    @Value("${reddit_client_secret}")
    private String REDDIT_CLIENT_SECRET;

    @Value("${vimeo_token}")
    private String VIMEO_TOKEN;

    @Value("${discord_bot_token}")
    private String DISCORD_TOKEN;

    PostService service;

    public Bot (PostService service) {
        this.service = service;
    }

    /**
     * Starts the discord bot
     */
    @Bean
    public void startDiscordBot() {
        try {
            JDA jda = JDABuilder.createDefault(DISCORD_TOKEN).build();
            jda.getPresence().setActivity(Activity.playing("Type !help"));
            jda.addEventListener(new RedditCommands(service));
            jda.addEventListener(new TextCommands());
            jda.addEventListener(new AdminCommands(service));
            System.out.println("Starting bot is done!");
        } catch (LoginException e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 3600000)
    public void hourDelay() {
        searchReddit();
        quotaLimitCheck();
        downloadAndUploadToVimeo();
    }

    /**
     * Searchs specialized subreddits and save them to database
     */
    private void searchReddit() {
        System.out.println("Program in search reddit");

        UserAgent userAgent = new UserAgent("Chrome", "com.example.demo.bot",
                "v0.1", REDDIT_USERNAME);
        NetworkAdapter networkAdapter = new OkHttpNetworkAdapter(userAgent);
        RedditClient redditClient = OAuthHelper.automatic(networkAdapter,
                Credentials.script(REDDIT_USERNAME, REDDIT_PASSWORD,
                        REDDIT_CLIENT_ID, REDDIT_CLIENT_SECRET));

        List<String> subreddits = Arrays.asList("Unexpected", "memes", "dankmemes", "greentext");

        List<DefaultPaginator<Submission>> paginatorList = new ArrayList<>();

        for (String s : subreddits) {
            paginatorList.add(redditClient.subreddit(s)
                    .posts()
                    .sorting(SubredditSort.TOP)
                    .timePeriod(TimePeriod.DAY)
                    .build());
        }

        for(DefaultPaginator<Submission> d : paginatorList) {
            Listing<Submission> submissions = d.next();
            for (Submission s : submissions) {

                if (!s.isNsfw()) {

                    int charLimit = s.getTitle().length() + s.getAuthor().length() + s.getSubreddit().length();

                    Post post = new Post(s.getUrl(), s.getSubreddit(), s.getTitle(), s.getAuthor(), s.getCreated());
                    post.setPermaUrl("https://reddit.com" + s.getPermalink());

                    if (s.getUrl().contains("https://v.redd.it") && charLimit <= 101) {

                        String fallbackUrl = Objects.requireNonNull(Objects.requireNonNull(s.getEmbeddedMedia()).getRedditVideo()).getFallbackUrl();
                        String fallbackVideo = fallbackUrl.substring(0, fallbackUrl.indexOf("?"));
                        String fallbackAudio = fallbackVideo.substring(0, fallbackVideo.indexOf("_") + 1) + "audio.mp4";
                        String baseDownloadUrl = "https://ds.redditsave.com/download.php?permalink=https://reddit.com";
                        String videoDownloadUrl = baseDownloadUrl + s.getPermalink() + "&video_url=";
                        String fallbackVideoDownloadUrl = videoDownloadUrl + fallbackUrl + "&audio_url=";
                        String fallbackVideoWithAudioDownloadUrl = fallbackVideoDownloadUrl + fallbackAudio + "?source=fallback";

                        post.setContentType("video");
                        post.setDownloadUrl(fallbackVideoWithAudioDownloadUrl);
                    }

                    else if (s.getUrl().contains(".gif") || s.getUrl().contains("gfycat.com")) {
                        post.setContentType("gif");
                    }

                    else if (s.getUrl().contains(".jpg") || s.getUrl().contains(".png")) {
                        post.setContentType("image");
                    }

                    else if (s.getUrl().contains("https://www.reddit.com/")) {
                        post.setContentType("text");
                    }

                    String queryPost = service.getByUrl(post.getUrl());

                    if (post.getUrl().equals(queryPost)) {
                        System.out.println("URL exist in database: " + queryPost);
                    }

                    else {
                        service.save(post);
                        System.out.println("Saved url to database: " + post.getUrl());
                    }
                }
            }

            try {
                Thread.sleep(30000);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Reddit search done!");
    }

    /**
     * This class make sure you have enough quota to upload video to vimeo
     * Vimeo has 10 video upload quota limit
     * But quota decreases when uploaded video deleted
     */
    private void quotaLimitCheck() {
        System.out.println("Program in quota limit check");
        final int quotaLimit = 10;

        Vimeo vimeo = new Vimeo(VIMEO_TOKEN);

        List<Post> nullVimeo = service.getVideoNullVimeo();
        List<Post> notNullVimeo = service.getVideoNotNullVimeo();

        //Delete if database have 10+ uploadable video
        //Free vimeo account only have 10 uploads per day.
        if (nullVimeo.size() > quotaLimit) {
            for (int i = nullVimeo.size() - 1; i >= quotaLimit; i--) {
                service.delete(nullVimeo.get(i));
                nullVimeo.remove(i);
            }
        }

        if (nullVimeo.size() + notNullVimeo.size() > quotaLimit && notNullVimeo.size() != 0) {
            int deleteVimeoCount = nullVimeo.size() + notNullVimeo.size() - quotaLimit;

            for(int i = 0; i < deleteVimeoCount; i++) {
                //Delete extra videos from vimeo to not exceed quota limit
                String videoEndPoint = "/videos" + notNullVimeo.get(i).getVimeoUrl().substring(17);
                try {
                    vimeo.removeVideo(videoEndPoint);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //Delete extra video from database
                service.delete(notNullVimeo.get(i));
            }
        }
        System.out.println("Quota limit check is done!");
    }

    /**
     * Checks database if vimeo url exists
     * if not download video from reddit url and
     * upload it to imgur and set vimeo_url to database
     */
    private void downloadAndUploadToVimeo() {
        System.out.println("Program in download and upload to vimeo");
        Vimeo vimeo = new Vimeo(VIMEO_TOKEN);

        List<Post> list = service.getVideoNullVimeo();
        System.out.println("File count: " + list.size());

        for (Post post : list) {

            //Gets download url from database
            URL url = null;
            try {
                url = new URL(post.getDownloadUrl());
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }

            //Download file and save it as temp.mp4
            try (InputStream in = Objects.requireNonNull(url).openStream();
                 ReadableByteChannel rbc = Channels.newChannel(in);
                 FileOutputStream fos = new FileOutputStream("temp.mp4")) {
                 fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            } catch (IOException e) {
                e.printStackTrace();
            }

            //Upload video to vimeo
            String videoEndPoint, vimeoUrl = null;
            try {
                videoEndPoint = vimeo.addVideo(new File("temp.mp4"), false);
                vimeo.updateVideoMetadata(videoEndPoint, "Title: " + post.getTitle() + " by: u/" + post.getAuthor() +
                        "\nPosted on: r/" + post.getSubreddit(),
                        "This video taken from reddit. Link to the video: " + post.getPermaUrl(),
                        "", "anybody", "public", false);
                System.out.println("Id of uploaded video to vimeo: " + post.getId() + " url: " + post.getUrl());
                vimeoUrl = "https://vimeo.com" + Objects.requireNonNull(videoEndPoint).substring(7);
            } catch (VimeoException | IOException e) {
                e.printStackTrace();
                System.out.println("Video upload failed: " + post.getId() + " url: " + post.getUrl());
            }

            post.setVimeoUrl(vimeoUrl);

            service.save(post);
        }

        //Deletes excess file
        File file = new File("temp.mp4");
        if (file.delete()) {
            System.out.println("File deleted successfully");
        }

        System.out.println("File uploading to vimeo is done!");
    }

    /**
     * Removes old posts from database
     */
    @Scheduled(fixedDelay = 86400000)
    private void removeOldPosts() {
        System.out.println("Program in remove old posts");
        Date date = new Date();
        final int dayDiff = 3;

        List<Post> list = service.findAll();

        for (Post post : list) {

            if (Math.abs(date.getDate() - post.getCreated().getDate()) >= dayDiff) {

                if(post.getContentType() !=  null  && post.getContentType().equals("video") && post.getVimeoUrl() != null) {

                    Vimeo vimeo = new Vimeo(VIMEO_TOKEN);

                    String videoEndPoint = "/videos" + post.getVimeoUrl().substring(17);

                    try {
                        vimeo.removeVideo(videoEndPoint);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                service.delete(post);
            }
        }
        System.out.println("Deleting old posts done!");
    }
}




