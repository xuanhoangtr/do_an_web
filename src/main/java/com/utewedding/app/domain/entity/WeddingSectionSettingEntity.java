package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "wedding_section_settings")
public class WeddingSectionSettingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "wedding_id", nullable = false, unique = true)
    private UUID weddingId;
    @Column(name = "primary_color", nullable = false, length = 7) private String primaryColor = "#C8A97E";
    @Column(name = "secondary_color", nullable = false, length = 7) private String secondaryColor = "#2F2F2F";
    @Column(name = "font_heading", nullable = false, length = 60) private String fontHeading = "playfair";
    @Column(name = "font_body", nullable = false, length = 60) private String fontBody = "inter";
    @Column(name = "background_music", length = 60) private String backgroundMusic;
    @Column(name = "opening_effect", nullable = false, length = 60) private String openingEffect = "fade";
    @Column(nullable = false) private boolean showCountdown = true;
    @Column(nullable = false) private boolean showStory = true;
    @Column(nullable = false) private boolean showGallery = true;
    @Column(nullable = false) private boolean showGuestbook = true;
    @Column(nullable = false) private boolean showBankAccounts;
    @Column(nullable = false) private boolean showRsvp = true;

    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String value) { primaryColor = value; }
    public String getSecondaryColor() { return secondaryColor; }
    public void setSecondaryColor(String value) { secondaryColor = value; }
    public String getFontHeading() { return fontHeading; }
    public void setFontHeading(String value) { fontHeading = value; }
    public String getFontBody() { return fontBody; }
    public void setFontBody(String value) { fontBody = value; }
    public String getBackgroundMusic() { return backgroundMusic; }
    public void setBackgroundMusic(String value) { backgroundMusic = value; }
    public String getOpeningEffect() { return openingEffect; }
    public void setOpeningEffect(String value) { openingEffect = value; }
    public boolean isShowCountdown() { return showCountdown; }
    public void setShowCountdown(boolean value) { showCountdown = value; }
    public boolean isShowStory() { return showStory; }
    public void setShowStory(boolean value) { showStory = value; }
    public boolean isShowGallery() { return showGallery; }
    public void setShowGallery(boolean value) { showGallery = value; }
    public boolean isShowGuestbook() { return showGuestbook; }
    public void setShowGuestbook(boolean value) { showGuestbook = value; }
    public boolean isShowBankAccounts() { return showBankAccounts; }
    public void setShowBankAccounts(boolean value) { showBankAccounts = value; }
    public boolean isShowRsvp() { return showRsvp; }
    public void setShowRsvp(boolean value) { showRsvp = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WeddingSectionSettingEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
