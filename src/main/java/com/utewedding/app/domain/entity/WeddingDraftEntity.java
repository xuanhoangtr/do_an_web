package com.utewedding.app.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "wedding_drafts")
public class WeddingDraftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "wedding_id", nullable = false, unique = true)
    private UUID weddingId;
    @Column(name = "bride_name", length = 120) private String brideName;
    @Column(name = "groom_name", length = 120) private String groomName;
    @Column(name = "bride_short_name", length = 60) private String brideShortName;
    @Column(name = "groom_short_name", length = 60) private String groomShortName;
    @Column(name = "bride_intro", length = 2000) private String brideIntro;
    @Column(name = "groom_intro", length = 2000) private String groomIntro;
    @Column(name = "bride_family_address", length = 500) private String brideFamilyAddress;
    @Column(name = "groom_family_address", length = 500) private String groomFamilyAddress;
    @Column(name = "family_note", length = 2000) private String familyNote;
    @Column(length = 200) private String headline;
    @Column(name = "invitation_message", length = 3000) private String invitationMessage;
    @Column(name = "thank_you_message", length = 2000) private String thankYouMessage;
    @Column(name = "footer_message", length = 1000) private String footerMessage;

    public UUID getId() { return id; }
    public UUID getWeddingId() { return weddingId; }
    public void setWeddingId(UUID weddingId) { this.weddingId = weddingId; }
    public String getBrideName() { return brideName; }
    public void setBrideName(String value) { brideName = value; }
    public String getGroomName() { return groomName; }
    public void setGroomName(String value) { groomName = value; }
    public String getBrideShortName() { return brideShortName; }
    public void setBrideShortName(String value) { brideShortName = value; }
    public String getGroomShortName() { return groomShortName; }
    public void setGroomShortName(String value) { groomShortName = value; }
    public String getBrideIntro() { return brideIntro; }
    public void setBrideIntro(String value) { brideIntro = value; }
    public String getGroomIntro() { return groomIntro; }
    public void setGroomIntro(String value) { groomIntro = value; }
    public String getBrideFamilyAddress() { return brideFamilyAddress; }
    public void setBrideFamilyAddress(String value) { brideFamilyAddress = value; }
    public String getGroomFamilyAddress() { return groomFamilyAddress; }
    public void setGroomFamilyAddress(String value) { groomFamilyAddress = value; }
    public String getFamilyNote() { return familyNote; }
    public void setFamilyNote(String value) { familyNote = value; }
    public String getHeadline() { return headline; }
    public void setHeadline(String value) { headline = value; }
    public String getInvitationMessage() { return invitationMessage; }
    public void setInvitationMessage(String value) { invitationMessage = value; }
    public String getThankYouMessage() { return thankYouMessage; }
    public void setThankYouMessage(String value) { thankYouMessage = value; }
    public String getFooterMessage() { return footerMessage; }
    public void setFooterMessage(String value) { footerMessage = value; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WeddingDraftEntity that)) return false;
        return com.utewedding.app.domain.EntityIdentity.same(this, other, id, that.id);
    }

    @Override
    public int hashCode() { return com.utewedding.app.domain.EntityIdentity.hashCode(this); }
}
