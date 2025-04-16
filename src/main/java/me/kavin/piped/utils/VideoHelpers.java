package me.kavin.piped.utils;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.utils.obj.db.Video;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.StatelessSession;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static me.kavin.piped.consts.Constants.YOUTUBE_SERVICE;
import static org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry;
import static org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;

public class VideoHelpers {
    public static void handleNewVideo(String url, long time, me.kavin.piped.utils.obj.db.Channel channel) {
        try {
            var extractor = YOUTUBE_SERVICE.getStreamExtractor(url);
            extractor.fetchPage();
            handleNewVideo(extractor, time, channel);
        } catch (Exception e) {
            ExceptionHandler.handle(e);
        }
    }

    public static void handleNewVideo(StreamInfo info, long time, me.kavin.piped.utils.obj.db.Channel channel) throws Exception {

        if (channel == null)
            channel = DatabaseHelper.getChannelFromId(
                    info.getUploaderUrl().substring("https://www.youtube.com/channel/".length()));

        // Prioritize actual upload date
        long infoTime = Optional.ofNullable(info.getUploadDate())
                .map(date -> date.offsetDateTime().toInstant().toEpochMilli())
                .orElse(time); // Fallback to provided time only if upload date is null

        if (channel != null
                && (System.currentTimeMillis() - infoTime) < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)) {

            info.setShortFormContent(isShort(info.getId()));
            String latestThumbnailUrl = info.getThumbnails() != null && !info.getThumbnails().isEmpty() ? info.getThumbnails().getLast().getUrl() : null;

            try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
                if (!DatabaseHelper.doesVideoExist(s, info.getId())) {

                    Video video = new Video(info.getId(), info.getName(), info.getViewCount(), info.getDuration(),
                            infoTime, // Use actual upload date
                            latestThumbnailUrl, info.isShortFormContent(), channel);

                    insertVideo(video);
                    return;
                }
            }
            // Video exists, update it
            updateVideo(info.getId(), info, infoTime); // Pass infoTime here as well

        }
    }

    public static void handleNewVideo(StreamExtractor extractor, long time, me.kavin.piped.utils.obj.db.Channel channel) throws Exception {

        if (channel == null)
            channel = DatabaseHelper.getChannelFromId(
                    extractor.getUploaderUrl().substring("https://www.youtube.com/channel/".length()));

        // Prioritize actual upload date
        long infoTime = Optional.ofNullable(extractor.getUploadDate())
                .map(date -> date.offsetDateTime().toInstant().toEpochMilli())
                .orElse(time); // Fallback to provided time only if upload date is null

        if (channel != null
                && (System.currentTimeMillis() - infoTime) < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)) {

            String latestThumbnailUrl = extractor.getThumbnails() != null && !extractor.getThumbnails().isEmpty() ? extractor.getThumbnails().getLast().getUrl() : null;

            try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
                if (!DatabaseHelper.doesVideoExist(s, extractor.getId())) {

                    boolean isShort = extractor.isShortFormContent() || isShort(extractor.getId());

                    Video video = new Video(extractor.getId(), extractor.getName(), extractor.getViewCount(), extractor.getLength(),
                            infoTime, // Use actual upload date
                            latestThumbnailUrl, isShort, channel);

                    insertVideo(video);
                    // No need to update if just inserted
                } else {
                    // Video exists, update it (thumbnail might have changed)
                    updateVideo(extractor.getId(), extractor.getViewCount(), extractor.getLength(), extractor.getName(), latestThumbnailUrl);
                }
            }
        }
    }

    /**
     * Handles inserting or updating a video based on a StreamInfoItem,
     * avoiding redundant fetching if the item details are sufficient.
     * Used primarily by the feed polling mechanism.
     *
     * @param item    The StreamInfoItem containing video details.
     * @param time    The timestamp to use if creating a new video record (e.g., from PubSub).
     * @param channel The Channel entity this video belongs to.
     */
    public static void handleNewVideo(StreamInfoItem item, long time, me.kavin.piped.utils.obj.db.Channel channel) {
        if (item == null || channel == null) return;

        // Prioritize actual upload date
        long infoTime = Optional.ofNullable(item.getUploadDate())
                .map(date -> date.offsetDateTime().toInstant().toEpochMilli())
                .orElse(time); // Fallback to provided time only if upload date is null

        if ((System.currentTimeMillis() - infoTime) >= TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)) {
            return; // Skip videos older than retention period
        }

        String videoId = null;
        try {
            videoId = YOUTUBE_SERVICE.getStreamLHFactory().getId(item.getUrl());
        } catch (ParsingException e) {
            ExceptionHandler.handle(e, "Failed to parse video ID from StreamInfoItem URL: " + item.getUrl());
            return;
        }

        if (StringUtils.isBlank(videoId)) return;

        String latestThumbnailUrl = item.getThumbnails() != null && !item.getThumbnails().isEmpty() ? item.getThumbnails().getLast().getUrl() : null;

        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            if (DatabaseHelper.doesVideoExist(s, videoId)) {
                // Video exists, update it (including thumbnail)
                updateVideo(videoId, item.getViewCount(), item.getDuration(), item.getName(), latestThumbnailUrl);
            } else {
                // Video doesn't exist, insert it
                boolean isShort = item.isShortFormContent();

                Video video = new Video(
                        videoId,
                        item.getName(),
                        item.getViewCount() > 0 ? item.getViewCount() : 0, // Ensure non-negative views
                        item.getDuration() > 0 ? item.getDuration() : 0, // Ensure non-negative duration
                        infoTime, // Use actual upload date
                        latestThumbnailUrl,
                        isShort,
                        channel
                );
                insertVideo(video); // Use the existing insert method
            }
        } catch (Exception e) {
            // Handle potential exceptions during DB check or insert/update
            ExceptionHandler.handle(e, "Error handling new video from StreamInfoItem: " + videoId);
        }
    }

    public static boolean isShort(String videoId) throws Exception {

        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(
                        getPreferredLocalization(), getPreferredContentCountry())
                        .value("url", "https://www.youtube.com/shorts/" + videoId)
                        .done())
                .getBytes(UTF_8);

        final JsonObject jsonResponse = getJsonPostResponse("navigation/resolve_url",
                body, getPreferredLocalization());

        return jsonResponse.getObject("endpoint").has("reelWatchEndpoint");
    }

    public static void updateVideo(String id, StreamInfo info, long time) {
        Multithreading.runAsync(() -> {
            try {
                String latestThumbnailUrl = info.getThumbnails() != null && !info.getThumbnails().isEmpty() ? info.getThumbnails().getLast().getUrl() : null;
                if (!updateVideo(id, info.getViewCount(), info.getDuration(), info.getName(), latestThumbnailUrl)) {
                    var channel = DatabaseHelper.getChannelFromId(StringUtils.substring(info.getUploaderUrl(), -24));
                    if (channel != null)
                        handleNewVideo(info, time, channel); // Pass time (which is infoTime here)
                }
            } catch (Exception e) {
                ExceptionHandler.handle(e);
            }
        });
    }

    public static void updateVideo(String id, StreamInfoItem item) {
         String latestThumbnailUrl = item.getThumbnails() != null && !item.getThumbnails().isEmpty() ? item.getThumbnails().getLast().getUrl() : null;
        updateVideo(id, item.getViewCount(), item.getDuration(), item.getName(), latestThumbnailUrl);
    }

    // Overload for backward compatibility or cases where thumbnail isn't available/needed
    public static boolean updateVideo(String id, long views, long duration, String title) {
        return updateVideo(id, views, duration, title, null); // Pass null for thumbnail
    }

    // Main update method now includes thumbnail
    public static boolean updateVideo(String id, long views, long duration, String title, String thumbnail) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {

            var cb = s.getCriteriaBuilder();
            var cu = cb.createCriteriaUpdate(Video.class);
            var root = cu.from(Video.class);
            cu.where(cb.equal(root.get("id"), id));

            boolean changed = false;
            if (duration > 0) {
                cu.set(root.get("duration"), duration);
                changed = true;
            }
            if (title != null) {
                cu.set(root.get("title"), title);
                changed = true;
            }
            if (views > 0) {
                cu.set(root.get("views"), views);
                changed = true;
            }
            // Only update thumbnail if a non-null value is provided
            if (thumbnail != null) {
                 // Optional: Add a check to see if the thumbnail actually changed before setting
                 // Video existingVideo = DatabaseHelper.getVideoFromId(s, id);
                 // if (existingVideo != null && !thumbnail.equals(existingVideo.getThumbnail())) {
                 //      cu.set(root.get("thumbnail"), thumbnail);
                 //      changed = true;
                 // } else if (existingVideo == null) { // Should not happen if updateVideo is called
                 //      cu.set(root.get("thumbnail"), thumbnail);
                 //      changed = true;
                 // }
                 cu.set(root.get("thumbnail"), thumbnail); // Simpler: always set if provided
                 changed = true;
            }

            if (!changed) {
                return true; // Nothing to update, but the video exists
            }

            long updated;

            var tr = s.beginTransaction();
            try {
                updated = s.createMutationQuery(cu).executeUpdate();
                tr.commit();
            } catch (Exception e) {
                tr.rollback();
                ExceptionHandler.handle(e, "Error updating video: " + id);
                // return true, so that we don't try to insert a video!
                return true;
            }

            return updated > 0;
        }
    }

    public static void insertVideo(Video video) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            var tr = s.beginTransaction();
            try {
                // Ensure thumbnail is not null before inserting
                String thumbnailToInsert = video.getThumbnail() != null ? video.getThumbnail() : ""; // Use empty string if null

                // Update the ON CONFLICT clause to also update the thumbnail
                s.createNativeMutationQuery(
                                "INSERT INTO videos (uploader_id,duration,is_short,thumbnail,title,uploaded,views,id) values " +
                                        "(:uploader_id,:duration,:is_short,:thumbnail,:title,:uploaded,:views,:id) ON CONFLICT (id) DO UPDATE SET " +
                                        "duration = excluded.duration, title = excluded.title, views = excluded.views, thumbnail = excluded.thumbnail" // Added thumbnail update
                        )
                        .setParameter("uploader_id", video.getChannel().getUploaderId())
                        .setParameter("duration", video.getDuration())
                        .setParameter("is_short", video.isShort())
                        .setParameter("thumbnail", thumbnailToInsert) // Use potentially modified thumbnail
                        .setParameter("title", video.getTitle())
                        .setParameter("uploaded", video.getUploaded()) // Ensure this uses the correct date
                        .setParameter("views", video.getViews())
                        .setParameter("id", video.getId())
                        .executeUpdate();
                tr.commit();
            } catch (Exception e) {
                tr.rollback();
                ExceptionHandler.handle(e);
            }
        }
    }
}