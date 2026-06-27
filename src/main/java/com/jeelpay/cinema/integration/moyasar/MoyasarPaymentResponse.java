package com.jeelpay.cinema.integration.moyasar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MoyasarPaymentResponse {

    private String id;
    private String status;
    private Long amount;
    private String currency;
    private String description;
    private Map<String, Object> source;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getSource() { return source; }
    public void setSource(Map<String, Object> source) { this.source = source; }

    public String getTransactionUrl() {
        if (source == null) return null;
        Object url = source.get("transaction_url");
        return url != null ? url.toString() : null;
    }

    public boolean isPaid() {
        return "paid".equalsIgnoreCase(status);
    }

    public boolean isRefunded() {
        return "refunded".equalsIgnoreCase(status);
    }
}
