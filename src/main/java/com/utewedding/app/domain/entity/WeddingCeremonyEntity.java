package com.utewedding.app.domain.entity;

import com.utewedding.app.domain.CeremonyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "wedding_ceremonies")
public class WeddingCeremonyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "wedding_id", nullable = false)
    private UUID weddingId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CeremonyType type = CeremonyType.THANH_HON;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(name = "start_date_time", nullable = false)
    private OffsetDateTime startDateTime;
    @Column(name = "end_date_time")
    private OffsetDateTime endDateTime;
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Ho_Chi_Minh";
    @Column(name = "venue_name", length = 200) private String venueName;
    @Column(name = "venue_address", length = 500) private String venueAddress;
    @Column(name = "google_maps_url", length = 2048) private String googleMapsUrl;
    @Column(length = 2000) private String description;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "is_visible", nullable = false) private boolean visible = true;

    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID value) { weddingId = value; }
    public CeremonyType getType() { return type; }
    public void setType(CeremonyType value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public OffsetDateTime getStartDateTime() { return startDateTime; }
    public void setStartDateTime(OffsetDateTime value) { startDateTime = value; }
    public OffsetDateTime getEndDateTime() { return endDateTime; }
    public void setEndDateTime(OffsetDateTime value) { endDateTime = value; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String value) { timezone = value; }
    public String getVenueName() { return venueName; }
    public void setVenueName(String value) { venueName = value; }
    public String getVenueAddress() { return venueAddress; }
    public void setVenueAddress(String value) { venueAddress = value; }
    public String getGoogleMapsUrl() { return googleMapsUrl; }
    public void setGoogleMapsUrl(String value) { googleMapsUrl = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int value) { displayOrder = value; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean value) { visible = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WeddingCeremonyEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
