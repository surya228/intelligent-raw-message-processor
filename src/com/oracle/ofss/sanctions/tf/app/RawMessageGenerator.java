package com.oracle.ofss.sanctions.tf.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RawMessageGenerator {
    public static void generateRawMessage() throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("\n=============================================================");
        System.out.println("                RAW MESSAGE GENERATOR STARTED                ");
        System.out.println("=============================================================");
        Connection connection = null;
        JSONArray rawMessageJsonArray = null;
        ResultSet rs = null;


        try {

            String srcFile = loadJsonFromFile(Constants.SOURCE_FILE_PATH);
            System.out.println("srcFile: "+srcFile);

            Properties props = new Properties();

            try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }

            connection = SQLUtility.getDbConnection();
            String watchlistType = props.getProperty(Constants.WATCHLIST_TYPE);
            String tableName = Constants.TABLE_WL_MAP.get(watchlistType);
            String tagName = props.getProperty(Constants.TAGNAME);
            String webService = props.getProperty(Constants.WEBSERVICE);
            String tansactionService = props.getProperty(Constants.TRANSACTION_SERVICE);
            String webserviceId = props.getProperty(Constants.WEBSERVICE_ID);
            rs = prepareQueryAndGetTableData(connection, props, tableName);

            rawMessageJsonArray = generateRawMessageJsonArray(rs,props,srcFile,tableName,tagName,webserviceId);

//            System.out.println(rawMessageJsonArray.toString(4).replace("<\\/", "</"));
//            writeJsonToFile(rawMessageJsonArray.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("<\\/", "</"));
//            writeJsonAsCSVFile(rawMessageJsonArray,tansactionService);
            writeJsonAsExcelFile(rawMessageJsonArray,tansactionService,tagName,webService,watchlistType);
            writeRawMessagesToJsonFile(rawMessageJsonArray);

            System.out.println("\n=============================================================");
            System.out.println("                 RAW MESSAGE GENERATOR ENDED                 ");
            System.out.println("=============================================================");
            long endTime = System.currentTimeMillis();

            System.out.println("Time taken by Raw Message Generator: " + (endTime - startTime) / 1000L + " seconds");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (connection != null) {
                try {
                    connection.close();
                    System.out.println("Connection closed.");
                } catch (SQLException e) {
                    System.err.println("Failed to close the connection:");
                    e.printStackTrace();
                }
            }
        }
    }

    private static ResultSet prepareQueryAndGetTableData(Connection connection, Properties props, String tableName) throws Exception {
        PreparedStatement pst = null;
        ResultSet rs = null;
        String filter="";
        if(props.containsKey(Constants.WHERE_CLAUSE)){
            filter = " where "+ props.get(Constants.WHERE_CLAUSE);
        }

        String query = "select * from "+tableName+" "+filter;
        System.out.println("SQL Query generated:: "+query);
        try {
            pst = connection.prepareStatement(query);
            rs = pst.executeQuery();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Something went wrong while preparing Query: ", e);
        }
        return rs;
    }

    public static JSONArray generateRawMessageJsonArray(ResultSet rs, Properties props, String srcFile, String tableName, String tagName, String webserviceId) throws Exception {
        JSONArray jsonArray = new JSONArray();
        int maxIndex = getMaxIndex(props, Constants.REPLACE_SRC);
        String temp;
        int updatedCount = 0;
        while(rs.next()) {
            temp=srcFile;
            if(temp != null) {
                for (int i = 1; i <= maxIndex; i++) {
                    String srcKey = Constants.REPLACE_SRC+"[" + i + "]";
                    String targetColumnKey = Constants.REPLACE_TARGET_COLUMN+"[" + i + "]";

                    String token = props.getProperty(srcKey);
                    String targetColumn = props.getProperty(targetColumnKey);
                    String tokenValue = rs.getString(targetColumn);
                    if(tokenValue==null) break;
                    String identifierToken =  props.getProperty(Constants.REPLACE_SRC+"[0]");
                    String identifierTargetColumn = props.getProperty(Constants.REPLACE_TARGET_COLUMN+"[0]");
                    String identifierToBeReplaced = rs.getString(identifierTargetColumn);
                    String uid = rs.getString(Constants.NUID);

                    String[] toBeReplacedValues = tokenValue.split(";");

                    for(String toBeReplaced : toBeReplacedValues) {
                        // 0 ced -> exact
                        updatedCount = createRawMsg(temp, toBeReplaced, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 0, uid, tagName, webserviceId);

                        if (props.getProperty(Constants.CED1).equalsIgnoreCase("Y")) { // 1 ced
                            List<String> oneCedList = generate1CedVariants(toBeReplaced);
                            for (String value : oneCedList) {
                                temp = srcFile;
                                updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 1, uid, tagName, webserviceId);
                            }
                        }

                        if (props.getProperty(Constants.CED2).equalsIgnoreCase("Y")) { // 2 ced
                            List<String> twoCedList = generate2CedVariants(toBeReplaced);
                            for (String value : twoCedList) {
                                temp = srcFile;
                                updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 2, uid, tagName, webserviceId);
                            }
                        }

                        if (props.getProperty(Constants.CED3).equalsIgnoreCase("Y")) { // 3 ced
                            List<String> threeCedList = generate3CedVariants(toBeReplaced);
                            for (String value : threeCedList) {
                                temp = srcFile;
                                updatedCount = createRawMsg(temp, value, identifierToBeReplaced, token, targetColumn, identifierToken, tableName, jsonArray, updatedCount, tokenValue, 3, uid, tagName, webserviceId);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("No. of raw message created:: "+ updatedCount);
        return jsonArray;

    }

    public static int createRawMsg(String temp, String value, String identifierToBeReplaced,
                      String token, String targetColumn, String identifierToken,
                      String tableName, JSONArray jsonArray, int updatedCount, String originalValue, int ced, String uid,
                                   String tagName, String webserviceId){
        if (value != null) {
            System.out.println("toBeReplaced: " + value + " originalValue: " + originalValue + "  token: " + token + "  column: "+ targetColumn + "  identifier: "+ identifierToBeReplaced + " ced: "+ ced);
            identifierToBeReplaced = Constants.IDEN_PREFIX+identifierToBeReplaced;
            temp = temp.replace(token, value);
            temp = temp.replace(identifierToken,identifierToBeReplaced);

            JSONObject tempJson = new JSONObject(temp);
            JSONObject additionalData = tempJson.getJSONObject(Constants.ADDITIONAL_DATA);
            additionalData.put(Constants.TABLE, tableName);
            additionalData.put(Constants.UID,uid);
            additionalData.put(Constants.COLUMN, targetColumn);
            additionalData.put(Constants.TOKEN, token);
            additionalData.put(Constants.VALUE, value);
            additionalData.put(Constants.ORIGINAL_VALUE, originalValue);
            additionalData.put(Constants.CED, ced);
            additionalData.put(Constants.TAGNAME,tagName);
            additionalData.put(Constants.WEBSERVICE_ID,webserviceId);
            additionalData.put(Constants.CED, ced);
            additionalData.put(Constants.IDEN_TOKEN, identifierToken);
            additionalData.put(Constants.IDEN_VALUE, identifierToBeReplaced);
            jsonArray.put(tempJson);

            updatedCount++;
        }
        return updatedCount;
    }
    public static List<String> generate1CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete
        if (len >= 1) variants.add(input.substring(1)); // remove first
        if (len >= 3) variants.add(input.substring(0, len / 2) + input.substring((len / 2) + 1)); // remove middle
        if (len >= 1) variants.add(input.substring(0, len - 1)); // remove last

//        // Insert
//        variants.add(INSERT_CHAR + input); // insert at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR); // insert at end

        return variants;
    }

    public static List<String> generate2CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete 2 characters
        if (len >= 3) {
            variants.add(input.substring(2)); // remove first two
            variants.add(input.substring(0, len / 2 - 1) + input.substring((len / 2) + 1)); // remove around middle
            variants.add(input.substring(0, len - 2)); // remove last two
        }

//        // Insert 2 characters
//        variants.add(INSERT_CHAR + INSERT_CHAR + input); // insert two at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR + INSERT_CHAR); // insert two at end

        return variants;
    }

    public static List<String> generate3CedVariants(String input) {
        List<String> variants = new ArrayList<>();
        int len = input.length();

        // Delete 3 characters
        if (len >= 4) {
            variants.add(input.substring(3)); // remove first 3
            variants.add(input.substring(0, len / 2 - 1) + input.substring((len / 2) + 2)); // remove around middle
            variants.add(input.substring(0, len - 3)); // remove last 3
        }

//        // Insert 3 characters
//        variants.add("" + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR + input); // insert 3 at start
//        variants.add(input.substring(0, len / 2) + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR + input.substring(len / 2)); // middle
//        variants.add(input + INSERT_CHAR + INSERT_CHAR + INSERT_CHAR); // end

        return variants;
    }

    private static int getMaxIndex(Properties props, String prefix) throws Exception {
        int maxIndex = 0;
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix + "[")) {
                try {
                    int index = Integer.parseInt(key.substring(prefix.length() + 1, key.length() - 1));
                    maxIndex = Math.max(maxIndex, index);
                } catch (NumberFormatException e) {
                    throw new Exception("Something went wrong while getting maxIndex",e);
                }
            }
        }
        return maxIndex;
    }

    public static String loadJsonFromFile(String filePath) {
        try {
            File file = new File(filePath);

            // Check if the file exists
            if (!file.exists()) {
                System.out.println("File not found: " + filePath);
                return null;
            }

            // Read the entire file content
            String jsonContent = Files.readString(Path.of(file.getPath()));
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
            SourceInputModel sourceInputModel = objectMapper.readValue(jsonContent, SourceInputModel.class);

            // Parse the JSON data using JSONObject
            JSONObject jsonObject = new JSONObject(sourceInputModel);

            // Convert the parsed JSON back to a string
            return jsonObject.toString(4);
        } catch (Exception e) {
            System.out.println("An error occurred while reading the file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    public static void writeJsonAsExcelFile(JSONArray jsonArray, String transactionService, String tagName, String webService, String watchlistType) throws IOException {

        // Create a subfolder "out" inside it
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();  // Create the folder if it doesn't exist
        }

        // Create the Excel workbook and sheet
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Output");

        // Header row
        String thirdColumn = Constants.MESSAGE + transactionService.toUpperCase();
        String[] headers = {
                Constants.SEQ_NO,
                Constants.RULE,
                thirdColumn,
                Constants.TAG,
                Constants.SOURCE_INPUT,
                Constants.TARGET_INPUT,
                Constants.TARGET_COLUMN,
                Constants.WATCHLIST,
                Constants.NUID
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Write JSON data
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String rawMessage = jsonObject.toString(4)
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("~~~~", "\\\\")
                    .replace("<\\/", "</");

            JSONObject additionalData = jsonObject.getJSONObject(Constants.ADDITIONAL_DATA);
            String sourceInput = additionalData.getString(Constants.VALUE);
            String targetInput = additionalData.getString(Constants.ORIGINAL_VALUE);
            String targetColumn = additionalData.getString(Constants.COLUMN);
            String uid = additionalData.getString(Constants.UID);
            int ced = additionalData.getInt((Constants.CED));

            String type = "";
            if(ced==0) type = Constants.EXACT;
            else type = Constants.FUZZY+ced+Constants.CED;

            String ruleName = webService+" "+type;

            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(i + 1);      // SeqNo
            row.createCell(1).setCellValue(ruleName);         // Rule Name
            row.createCell(2).setCellValue(rawMessage); // Message <SERVICE>
            row.createCell(3).setCellValue(tagName);         // Tag
            row.createCell(4).setCellValue(sourceInput);         // Source Input
            row.createCell(5).setCellValue(targetInput);         // Target Input
            row.createCell(6).setCellValue(targetColumn);         // Target Column
            row.createCell(7).setCellValue(watchlistType);         // Watchlist
            row.createCell(8).setCellValue(uid);         // N_UID

        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        FileOutputStream fileOut = new FileOutputStream(Constants.OUTPUT_XLSX_FILE_PATH);
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();

        System.out.println("Successfully wrote to Excel ("+Constants.OUTPUT_FILE_NAME+".xlsx) file.");
    }

    public static void writeRawMessagesToJsonFile(JSONArray jsonArray) throws IOException {
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();
        }

        File outputFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_NAME+".json");

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(jsonArray.toString(4).getBytes(Constants.ENCODER));
        }

        System.out.println("Successfully wrote raw messages to JSON ("+Constants.OUTPUT_FILE_NAME+".json) file.");
    }
}
