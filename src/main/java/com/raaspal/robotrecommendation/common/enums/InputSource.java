package com.raaspal.robotrecommendation.common.enums;

public enum InputSource {
    /** Customer or team filled the structured web form directly. */
    WEB_FORM,

    /** Requirement was parsed from an uploaded CSV file. */
    CSV_IMPORT,

    /** Structured fields were pre-filled by AI from an uploaded PDF or image. */
    FILE_EXTRACTED
}