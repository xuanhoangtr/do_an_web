package com.utewedding.app.service.web;

public class AuditLogFilter {
    private String actor = "";
    private String action = "";
    private String resourceType = "";
    private String resourceId = "";
    private String from = "";
    private String to = "";
    private String query = "";

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
}
