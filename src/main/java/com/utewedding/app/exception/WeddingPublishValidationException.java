package com.utewedding.app.exception;

public final class WeddingPublishValidationException extends BusinessConflictException {
    public enum EditorStep {
        COUPLE("couple", "Mở bước 01 Cặp đôi"),
        EVENT("event", "Mở bước 02 Hôn lễ");

        private final String id;
        private final String actionLabel;

        EditorStep(String id, String actionLabel) {
            this.id = id;
            this.actionLabel = actionLabel;
        }

        public String id() {
            return id;
        }

        public String actionLabel() {
            return actionLabel;
        }
    }

    private final EditorStep editorStep;

    public WeddingPublishValidationException(String message, EditorStep editorStep) {
        super(message);
        this.editorStep = editorStep;
    }

    public EditorStep editorStep() {
        return editorStep;
    }
}
