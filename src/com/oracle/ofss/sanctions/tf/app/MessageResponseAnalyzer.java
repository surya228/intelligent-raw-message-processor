package com.oracle.ofss.sanctions.tf.app;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

public class MessageResponseAnalyzer {
    public static void analyseResponseAndPrepareResults() throws IOException {
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");
        System.out.println("-----------------RESPONSE ANALYZER STARTED-------------------");
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");
        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();
        String configFilePath = parentDir+File.separator+"bin"+File.separator+"config.properties";
        String filePath = parentDir+File.separator+"out"+File.separator+"output.xlsx";
        Properties props = new Properties();

        try (FileReader reader = new FileReader(configFilePath)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }
        String tagName = props.getProperty("tagName");
        System.out.println("tagName: "+ tagName);
        File excelFile = new File(filePath);
        processEachJsonInExcel(excelFile,tagName);
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");
        System.out.println("------------------RESPONSE ANALYZER ENDED--------------------");
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");
    }

    public static void processEachJsonInExcel(File excelFile, String tagName) throws IOException {

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Cell jsonCell = row.getCell(2); // 3rd column

                if (jsonCell != null && jsonCell.getCellType() == CellType.STRING) {
                    String jsonText = jsonCell.getStringCellValue().trim();

                    try {
                        JSONObject jsonObject = new JSONObject(jsonText);
                        processJson(jsonObject, row.getRowNum());
                    } catch (Exception e) {
                        System.err.println("[WARN] Invalid JSON at row " + (row.getRowNum() + 1) + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void processJson(JSONObject jsonObject, int rowNum) {
        System.out.println("[INFO] Processing row " + rowNum + "...");
//        System.out.println(jsonObject.toString(2));


    }


}
