package com.insureflow.web.admin;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Upload damage photos to Cloudinary.
 * Returns public URLs to include in claim submission.
 *
 * Flow:
 * 1. Mobile/Postman sends photo file(s) to POST /api/v1/photos/upload
 * 2. InsureFlow uploads to Cloudinary
 * 3. Returns public URL(s)
 * 4. Client includes URL(s) in POST /api/v1/claims photoUrls field
 */
@RestController
@RequestMapping("/api/v1/photos")
public class PhotoUploadController {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadController.class);

    private final Cloudinary cloudinary;

    public PhotoUploadController(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Upload one or multiple damage photos.
     * Returns the public Cloudinary URLs to use in claim submission.
     *
     * POST /api/v1/photos/upload
     * Content-Type: multipart/form-data
     * Body: files (one or more image files)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No files provided"));
        }

        List<String> uploadedUrls  = new ArrayList<>();
        List<String> failedFiles   = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                log.info("[PHOTO] Uploading '{}' size={}KB",
                        file.getOriginalFilename(), file.getSize() / 1024);

                @SuppressWarnings("unchecked")
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
                uploadedUrls.add(url);

                log.info("[PHOTO] Uploaded → {}", url);

            } catch (Exception e) {
                log.error("[PHOTO] Failed to upload '{}': {}",
                        file.getOriginalFilename(), e.getMessage());
                failedFiles.add(file.getOriginalFilename());
            }
        }

        if (uploadedUrls.isEmpty()) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "All uploads failed", "failed", failedFiles));
        }

        return ResponseEntity.ok(Map.of(
                "uploaded",    uploadedUrls.size(),
                "photoUrls",   uploadedUrls,
                "failed",      failedFiles,
                "instruction", "Utilise ces URLs dans le champ photoUrls de POST /api/v1/claims"
        ));
    }

    /**
     * Upload a single photo — convenience endpoint.
     * POST /api/v1/photos/upload/single
     */
    @PostMapping("/upload/single")
    public ResponseEntity<Map<String, Object>> uploadSingle(
            @RequestParam("file") MultipartFile file) {
        return upload(List.of(file));
    }
}
