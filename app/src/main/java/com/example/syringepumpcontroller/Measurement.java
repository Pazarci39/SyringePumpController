package com.example.syringepumpcontroller;

public class Measurement {
    private int id;
    private String dateTime;
    private double flowRate;
    private double volume;
    private String duration;
    private String notes;

    // Boş constructor
    public Measurement() {
    }

    // Tam constructor
    public Measurement(String dateTime, double flowRate, double volume, String duration, String notes) {
        this.dateTime = dateTime;
        this.flowRate = flowRate;
        this.volume = volume;
        this.duration = duration;
        this.notes = notes;
    }

    // Getter ve setter metodları
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDateTime() {
        return dateTime;
    }

    public void setDateTime(String dateTime) {
        this.dateTime = dateTime;
    }

    public double getFlowRate() {
        return flowRate;
    }

    public void setFlowRate(double flowRate) {
        this.flowRate = flowRate;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}