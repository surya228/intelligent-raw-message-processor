package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Constants {
    public static final Map<String, String> TABLE_WL_MAP;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("COUNTRY", "FCC_TF_DIM_COUNTRY");
        map.put("CITY", "FCC_TF_DIM_CITY");
        map.put("GOODS", "FCC_TF_DIM_GOODS");
        map.put("PORT", "FCC_TF_DIM_PORT");
        map.put("STOP_KEYWORDS", "FCC_TF_DIM_STOPKEYWORDS");
        map.put("IDENTIFIER", "FCC_DIM_IDENTIFIER");
        map.put("WCPREM", "FCC_WL_WC_PREMIUM");
        map.put("WCSTANDARD", "FCC_WL_WC_STANDARD");
        map.put("DJW", "FCC_WL_DJW");
        map.put("OFAC", "FCC_WL_OFAC");
        map.put("HMT", "FCC_WL_HMT");
        map.put("EU", "FCC_WL_EUROPEAN_UNION");
        map.put("UN", "FCC_WL_UN");
        map.put("PRIVATELIST", "FCC_WL_PRIVATELIST");

        TABLE_WL_MAP = Collections.unmodifiableMap(map); // Make it read-only
    }

    public static String CURRENT_DIRECTORY = System.getProperty("user.dir");
    public static File PARENT_DIRECTORY = new File(CURRENT_DIRECTORY).getParentFile();
    public static String SOURCE_FILE_PATH = PARENT_DIRECTORY+File.separator+"bin"+File.separator+"source.json";
    public static String CONFIG_FILE_PATH = PARENT_DIRECTORY+File.separator+"bin"+File.separator+"config.properties";
    public static File OUTPUT_FOLDER = new File(Constants.PARENT_DIRECTORY, "out");
    public static String OUTPUT_JSON_FILENAME = OUTPUT_FOLDER+File.separator+"output.json";
    public static String OUTPUT_CSV_FILENAME = OUTPUT_FOLDER+File.separator+"output.csv";
    public static File OUTPUT_XLSX_FILE= new File(OUTPUT_FOLDER,"output.xlsx");


}
