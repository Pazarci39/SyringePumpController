package com.example.syringepumpcontroller;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "measurement_db";
    private static final int DATABASE_VERSION = 1;

    // Tablo ve sütun isimleri
    public static final String TABLE_MEASUREMENTS = "measurements";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_DATE_TIME = "date_time";
    public static final String COLUMN_FLOW_RATE = "flow_rate";
    public static final String COLUMN_VOLUME = "volume";
    public static final String COLUMN_DURATION = "duration";
    public static final String COLUMN_NOTES = "notes";

    // Tablo oluşturma sorgusu
    private static final String CREATE_TABLE_MEASUREMENTS = "CREATE TABLE " +
            TABLE_MEASUREMENTS + "(" +
            COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_DATE_TIME + " TEXT NOT NULL, " +
            COLUMN_FLOW_RATE + " REAL NOT NULL, " +
            COLUMN_VOLUME + " REAL NOT NULL, " +
            COLUMN_DURATION + " TEXT NOT NULL, " +
            COLUMN_NOTES + " TEXT" +
            ")";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_MEASUREMENTS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MEASUREMENTS);
        onCreate(db);
    }

    // Yeni ölçüm ekleme metodu
    public long addMeasurement(Measurement measurement) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(COLUMN_DATE_TIME, measurement.getDateTime());
        values.put(COLUMN_FLOW_RATE, measurement.getFlowRate());
        values.put(COLUMN_VOLUME, measurement.getVolume());
        values.put(COLUMN_DURATION, measurement.getDuration());
        values.put(COLUMN_NOTES, measurement.getNotes());

        // Veritabanına ekleme ve ID'yi döndürme
        long id = db.insert(TABLE_MEASUREMENTS, null, values);
        db.close();

        return id;
    }

    // Tüm ölçümleri getiren metod
    public List<Measurement> getAllMeasurements() {
        List<Measurement> measurementList = new ArrayList<>();

        String selectQuery = "SELECT * FROM " + TABLE_MEASUREMENTS + " ORDER BY " + COLUMN_ID + " DESC";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        if (cursor.moveToFirst()) {
            do {
                Measurement measurement = new Measurement();
                measurement.setId(cursor.getInt(cursor.getColumnIndex(COLUMN_ID)));
                measurement.setDateTime(cursor.getString(cursor.getColumnIndex(COLUMN_DATE_TIME)));
                measurement.setFlowRate(cursor.getDouble(cursor.getColumnIndex(COLUMN_FLOW_RATE)));
                measurement.setVolume(cursor.getDouble(cursor.getColumnIndex(COLUMN_VOLUME)));
                measurement.setDuration(cursor.getString(cursor.getColumnIndex(COLUMN_DURATION)));
                measurement.setNotes(cursor.getString(cursor.getColumnIndex(COLUMN_NOTES)));

                measurementList.add(measurement);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();

        return measurementList;
    }

    // ID'ye göre ölçüm silme
    public void deleteMeasurement(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_MEASUREMENTS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        db.close();
    }

    // Tüm ölçümleri silme
    public void deleteAllMeasurements() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_MEASUREMENTS, null, null);
        db.close();
    }
}