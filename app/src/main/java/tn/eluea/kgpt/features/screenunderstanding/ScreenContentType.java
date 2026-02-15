package tn.eluea.kgpt.features.screenunderstanding;

/**
 * Very lightweight classification for "page-like" text.
 *
 * NOTE: This is intentionally heuristic (no OCR, no heavy parsing).
 */
public enum ScreenContentType {
    SHOPPING,
    NEWS,
    GENERIC
}
