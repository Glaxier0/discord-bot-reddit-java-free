package com.discord.bot.Dao;

import com.discord.bot.Entity.Post;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface PostRepositoryCustom {

    @Query(value = "SELECT url from posts where url = :url", nativeQuery = true)
    String getByUrl(@Param("url") String url);

    @Query(value = "SELECT * FROM posts WHERE type = 'video' AND vimeo_url IS NULL", nativeQuery = true)
    List<Post> getVideoNullVimeo ();

    @Query(value = "SELECT * FROM posts WHERE type = 'video' AND vimeo_url IS NOT NULL", nativeQuery = true)
    List<Post> getVideoNotNullVimeo ();

    @Query(value = "SELECT * FROM posts WHERE type IS NOT NULL AND subreddit = :subreddit",nativeQuery = true)
    List<Post> getPosts(@Param("subreddit") String subreddit);

    @Query(value = "SELECT subreddit, COUNT(subreddit) FROM posts GROUP BY subreddit", nativeQuery = true)
    List<String> getSubredditCount();
}
