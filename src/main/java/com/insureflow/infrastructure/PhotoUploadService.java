package com.insureflow.infrastructure;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles uploading damage photos to Cloudinary.
 *
 * Called automatically when a claim is submitted via POST /api/v1/claims/with-photos.
 * The client sends raw photo files — this service uploads them to Cloudinary
 * and returns the public URLs that get stored in the claim and passed to EstimatorAgent.
 *
 * Why Cloudinary?
 * - EstimatorAgent needs publicly accessible URLs to fetch the images
 * - Storing raw bytes in Postgres is wasteful and slow
 * - Cloudinary free tier gives 25GB which is more than enough for a PFE demo
 */
@Service
public class  PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);

    private final Cloudinary cloudinary;

    public PhotoUploadService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Uploads all provided photos to Cloudinary.
     * Skips null or empty files silently.
     * Returns list of public HTTPS URLs.
     */
    public List<String> uploadAll(List<MultipartFile> files) {
        List<String> urls = new ArrayList<>();

        if (files == null || files.isEmpty()) {
            log.debug("[PHOTO] No photos to upload");
            return urls;
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            try {
                String url = uploadOne(file);
                urls.add(url);
            } catch (Exception e) {
                log.error("[PHOTO] Failed to upload '{}': {}",
                        file.getOriginalFilename(), e.getMessage());
                // Don't fail the whole claim — continue with other photos
            }
        }

        log.info("[PHOTO] Uploaded {}/{} photos successfully",
                urls.size(), files.size());
        return urls;
    }

    @SuppressWarnings("unchecked")
    private String uploadOne(MultipartFile file) throws Exception {
        log.info("[PHOTO] Uploading '{}' {}KB",
                file.getOriginalFilename(), file.getSize() / 1024);

        Map<String, Object> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder",          "insureflow-claims",
                        "resource_type",   "image",
                        "use_filename",    true,
                        "unique_filename", true
                )
        );

        String url = (String) result.get("secure_url");
        log.info("[PHOTO] Uploaded → {}", url);
        return url;
    }
}