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

            connection = getDbConnection();
            String tableName = Constants.TABLE_WL_MAP.get(props.getProperty("watchListType"));
            String tansactionService = props.getProperty("msgPosting.transactionService");
            rs = prepareQueryAndGetTableData(connection, props, tableName);

            rawMessageJsonArray = generateRawMessageJsonArray(rs,props,srcFile,tableName);

//            System.out.println(rawMessageJsonArray.toString(4).replace("<\\/", "</"));
//            writeJsonToFile(rawMessageJsonArray.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("<\\/", "</"));
//            writeJsonAsCSVFile(rawMessageJsonArray,tansactionService);
            writeJsonAsExcelFile(rawMessageJsonArray,tansactionService);

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
        if(props.containsKey("whereClause")){
            filter = " where "+ props.get("whereClause");
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

    private static JSONArray generateRawMessageJsonArray(ResultSet rs, Properties props, String srcFile, String tableName) throws Exception {
        JSONArray jsonArray = new JSONArray();
        int maxIndex = getMaxIndex(props, "replace.src");
        String temp;
        int updatedCount = 0;
        while(rs.next()) {
            temp=srcFile;
            if(temp != null) {
                for (int i = 1; i <= maxIndex; i++) {
                    String srcKey = "replace.src[" + i + "]";
                    String targetColumnKey = "replace.targetColumn[" + i + "]";

                    String token = props.getProperty(srcKey);
                    String targetColumn = props.getProperty(targetColumnKey);

                    String identifierToken =  props.getProperty("replace.src[0]");
                    String identifierTargetColumn = props.getProperty("replace.targetColumn[0]");

                    String toBeReplaced = rs.getString(targetColumn);
                    String identifierToBeReplaced = rs.getString(identifierTargetColumn);

                    // 0 ced -> exact
                    updatedCount = createRawMsg(temp,toBeReplaced,identifierToBeReplaced,token,targetColumn,identifierToken,tableName,jsonArray,updatedCount,toBeReplaced,0);

                    if(props.getProperty("ced1").equalsIgnoreCase("Y")){ // 1 ced
                        List<String> oneCedList = generate1CedVariants(toBeReplaced);
                        for(String value : oneCedList){
                            temp = srcFile;
                            updatedCount = createRawMsg(temp,value,identifierToBeReplaced,token,targetColumn,identifierToken,tableName,jsonArray,updatedCount,toBeReplaced,1);
                        }
                    }

                    if(props.getProperty("ced2").equalsIgnoreCase("Y")){ // 2 ced
                        List<String> twoCedList = generate2CedVariants(toBeReplaced);
                        for(String value : twoCedList){
                            temp = srcFile;
                            updatedCount = createRawMsg(temp,value,identifierToBeReplaced,token,targetColumn,identifierToken,tableName,jsonArray,updatedCount,toBeReplaced,2);
                        }
                    }

                    if(props.getProperty("ced3").equalsIgnoreCase("Y")){ // 3 ced
                        List<String> threeCedList = generate3CedVariants(toBeReplaced);
                        for(String value : threeCedList){
                            temp = srcFile;
                            updatedCount = createRawMsg(temp,value,identifierToBeReplaced,token,targetColumn,identifierToken,tableName,jsonArray,updatedCount,toBeReplaced,3);
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
                      String tableName, JSONArray jsonArray, int updatedCount, String originalValue, int ced){
        if (value != null) {
            System.out.println("toBeReplaced: " + value + " originalValue: " + originalValue + "  token: " + token + "  column: "+ targetColumn + "  identifier: "+ identifierToBeReplaced + " ced: "+ ced);
            temp = temp.replace(token, value);
            temp = temp.replace(identifierToken,identifierToBeReplaced);

            JSONObject tempJson = new JSONObject(temp);
            JSONObject additionalData = tempJson.getJSONObject("additionalData");
            additionalData.put("table", tableName);
            additionalData.put("column", targetColumn);
            additionalData.put("token", token);
            additionalData.put("value", value);
            additionalData.put("originalValue", originalValue);
            additionalData.put("ced", ced);
            additionalData.put("identifierToken", identifierToken);
            additionalData.put("identifierValue", identifierToBeReplaced);
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

    public static Connection getDbConnection() throws Exception {

        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        String jdbcUrl = props.getProperty("jdbcurl");
        String jdbcDriver = props.getProperty("jdbcdriver");
        String username = props.getProperty("username");
        String password = props.getProperty("password");
        String walletname = props.getProperty("walletName");
        String tnsAdminPath = Constants.PARENT_DIRECTORY+File.separator+"bin"+File.separator+walletname;

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.tns_admin", tnsAdminPath);
        Class.forName(jdbcDriver);
        Connection connection = DriverManager.getConnection(jdbcUrl,properties);
        System.out.println("Connection established successfully!");
        return connection;
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

    public static void writeJsonToFile(String content){

        File outputFolder = new File(Constants.PARENT_DIRECTORY, "out");
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();  // Create the folder if it doesn't exist
        }
        try (FileWriter file = new FileWriter(Constants.OUTPUT_JSON_FILENAME)) {
            file.write(content);
            System.out.println("Successfully wrote to file.");
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

    public static void writeJsonAsCSVFile(JSONArray jsonArray, String tansactionService) throws IOException {
        // Create a subfolder "output" inside it
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();  // Create the folder if it doesn't exist
        }
        try (CSVWriter writer = new CSVWriter(new FileWriter(Constants.OUTPUT_CSV_FILENAME))) {
            // Write the header
            String thirdColumn = "Message "+tansactionService.toUpperCase();
            String[] headers = {"SeqNo", "Rule Name", thirdColumn, "Message Response"};
            writer.writeNext(headers);

            // Iterate over the JSON array and write each object as a row in the CSV file
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String messageIso = jsonObject.toString(4).replace("\\r", "\r").replace("\\n", "\n").replace("~~~~", "\\\\").replace("<\\/", "</");
                String[] row = {String.valueOf(i + 1), "", messageIso, ""};
                writer.writeNext(row);
            }
        }
        System.out.println("Successfully wrote to CSV file.");
    }


    public static void writeJsonAsExcelFile(JSONArray jsonArray, String transactionService) throws IOException {

        // Create a subfolder "out" inside it
        if (!Constants.OUTPUT_FOLDER.exists()) {
            Constants.OUTPUT_FOLDER.mkdirs();  // Create the folder if it doesn't exist
        }

        // Create the Excel workbook and sheet
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Output");

        // Header row
        String thirdColumn = "Message " + transactionService.toUpperCase();
        String[] headers = {"SeqNo", "Rule Name", thirdColumn, "Transaction Token", "Match Count", "Status", "Feedback Status", "# True Positives"};

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Write JSON data
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String messageIso = jsonObject.toString(4)
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("~~~~", "\\\\")
                    .replace("<\\/", "</");

            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(i + 1);      // SeqNo
            row.createCell(1).setCellValue("");         // Rule Name
            row.createCell(2).setCellValue(messageIso); // Message <SERVICE>
            row.createCell(3).setCellValue("");         // Transaction Token
            row.createCell(4).setCellValue("");         // Match Count
            row.createCell(5).setCellValue("");         // Status
            row.createCell(6).setCellValue("");         // Feedback Status
            row.createCell(7).setCellValue("");         // # True Positives
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        FileOutputStream fileOut = new FileOutputStream(Constants.OUTPUT_XLSX_FILE);
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();

        System.out.println("Successfully wrote to Excel (output.xlsx) file.");
    }


    public static String replaceNewlinesInJsonStrings(String json) throws JsonProcessingException {

        Pattern pattern = Pattern.compile("\"rawMessage\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);

        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String rawValue = matcher.group(1);
            System.out.println("matched rawValue:"+rawValue);
            String escaped = rawValue
                    .replace("\\\\", "\\")
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("\\", "\\\\")   // escape existing backslashes
                    .replace("\r", "\\r")    // escape CR
                    .replace("\n", "\\n");   // escape LF
            matcher.appendReplacement(result, "\"rawMessage\": \"" + Matcher.quoteReplacement(escaped) + "\"");
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
