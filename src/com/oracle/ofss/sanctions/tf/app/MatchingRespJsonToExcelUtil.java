package com.oracle.ofss.sanctions.tf.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MatchingRespJsonToExcelUtil {

	static String folderName = "NarrativeMatchingResults";
	static String fileName = "20250526_RUN2";
	static Map<String, String> searchMap;
	static Map<String, Integer> searchHitsCountMap;

	public static void convertJsonToExcel(String url) throws Exception {
		String directoryPath = "src/resources/" + folderName;
		File directory = new File(directoryPath);
		if (directory.mkdirs()) {
			System.out.println("Directory created successfully.");
		} else {
			System.out.println("Failed to create directory.");
		}
																										// path
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			Map<String, List<String[]>> excelMap = new LinkedHashMap<>();
			searchMap = new HashMap<>();
			searchHitsCountMap = new HashMap<>();
			Map<String, String> jsonMap = getJsonRecords(url);

			for (Map.Entry<String, String> entry: jsonMap.entrySet() ) {
				try {
					// Read JSON file as JsonNode (generic JSON structure)
					JsonNode jsonNode = objectMapper.readTree(entry.getValue());
					String filename = entry.getKey();
					System.out.println("Processing file: " + filename);
					List<String[]> output = new ArrayList<>();

					if (jsonNode.has("matches") && jsonNode.get("matches").isArray()) {
						for (JsonNode match : jsonNode.get("matches")) {
							if (match.has("matchCols") && match.get("matchCols").isArray()) {
								StringBuilder searchString = new StringBuilder();
								StringBuilder targetString = new StringBuilder();
								StringBuilder matchedCol = new StringBuilder();
								StringBuilder score = new StringBuilder();
								StringBuilder featureVector = new StringBuilder();

								for (JsonNode col : match.get("matchCols")) {
									if (!score.isEmpty()) {
										searchString.append(",");
										targetString.append(",");
										matchedCol.append(",");
										score.append(",");
										featureVector.append(",");
									}
									searchString.append(col.get("searchStringTrans").asText());
									targetString.append(col.get("colValueTrans").asText());
									matchedCol.append(col.get("colName").asText());
									score.append(col.get("score").asDouble());
									featureVector.append(col.get("featureVector").asText());
								}
								output.add(new String[] { matchedCol.toString(), searchString.toString(),
										targetString.toString(), match.get("indexName").asText(),
										match.get("id").asText(), score.toString(), featureVector.toString() });
							}
						}
						if(jsonNode.get("matches").size() == 0){
							output.add(new String[] { "", "","", "","", "", "" });
						}
						searchHitsCountMap.put(filename, jsonNode.get("matches").size());
					}
					excelMap.put(filename, output);
					writeResultsToExcel(excelMap, directoryPath + "\\" + fileName + ".xlsx");

				} catch (IOException e) {
					System.err.println("Error reading file: " + entry.getKey());
					e.printStackTrace();
				}
			}
		} catch (IOException e) {
			System.err.println("Error reading directory: " + directoryPath);
			e.printStackTrace();
		}
	}

	public static Map<String, String> getJsonRecords(String url) throws Exception {

		Connection connection = null;
		PreparedStatement pst = null;
		ResultSet rs = null;
		Map<String,String> jsonMap = new LinkedHashMap<>();

		try {
			connection = getDbConnection(url);
			String query = "select JSON_VALUE(C_REQUEST_JSON, '$[0].ruleName') ruleName, t.*, JSON_VALUE(C_REQUEST_JSON, '$[0].indexCols[0].searchString[0]') searchString, SUBSTR(JSON_VALUE(C_MATCHED_RESULT, '$.message'),55) matches_cnt from fcc_mr_matched_result_rt t " +
					" where JSON_VALUE(C_REQUEST_JSON, '$[0].ruleName')='Narrative Full Name Matched' and n_request_id like '%1' and n_request_id>=7951 and n_request_id<=9991 order by n_request_id ";
			System.out.println("SQL:: "+query);
			pst = connection.prepareStatement(query);
			rs = pst.executeQuery();
			System.out.println("Query execution completed");

			while(rs.next()){
				Clob clob = rs.getClob("C_MATCHED_RESULT");
				try (Reader reader = clob.getCharacterStream()) {
					StringBuilder sb = new StringBuilder();
					char[] buffer = new char[1024];
					int bytesRead;
					while ((bytesRead = reader.read(buffer)) != -1) {
						sb.append(buffer, 0, bytesRead);
					}
					jsonMap.put(rs.getString("N_REQUEST_ID"), sb.toString());
				}
				searchMap.put(rs.getString("N_REQUEST_ID"), rs.getString("SEARCHSTRING"));
			}
			System.out.println("Fetching data from DB completed.");
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (rs != null) {
				rs.close();
			}
			if (pst != null) {
				pst.close();
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

		return jsonMap;
	}

	public static Connection getDbConnection(String url) throws Exception {
		Class.forName("oracle.jdbc.driver.OracleDriver");
		Connection connection = DriverManager.getConnection(url);
		System.out.println("Connection established successfully!");
		return connection;
	}

	private static void writeResultsToExcel(Map<String, List<String[]>> results, String filePath) {
		Workbook workbook = new XSSFWorkbook();

		CellStyle headerStyle = workbook.createCellStyle();
		Font headerFont = workbook.createFont();
		headerFont.setBold(true); // Bold text
		headerStyle.setFont(headerFont);
		headerStyle.setFillForegroundColor(IndexedColors.ORANGE.getIndex()); // Background Color Orange
		headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

		for (String key : results.keySet()) {
			Sheet sheet = workbook.createSheet(key);

			Row headerRow = sheet.createRow(0);
			int colIndex = 0;
			List<String> headers = List.of("colName", "searchStringTrans", "colValueTrans", "indexName", "wlId",
					"score", "featureVector");
			for (String hdr : headers) {
				Cell cell = headerRow.createCell(colIndex++);
				cell.setCellValue(hdr);
				cell.setCellStyle(headerStyle);
			}

			Cell cell = headerRow.createCell(8);
			cell.setCellValue("Hits Count");
			cell.setCellStyle(headerStyle);

			cell = headerRow.createCell(9);
			cell.setCellValue("Input Text");
			cell.setCellStyle(headerStyle);

			int rowNum = 1;
			for (String[] result : results.get(key)) {
				Row row = sheet.createRow(rowNum++);
				row.createCell(0).setCellValue(result[0]);
				row.createCell(1).setCellValue(result[1]);
				row.createCell(2).setCellValue(result[2]);
				row.createCell(3).setCellValue(result[3]);
				row.createCell(4).setCellValue(result[4]);
				row.createCell(5).setCellValue(result[5]);
				row.createCell(6).setCellValue(result[6]);

				if (rowNum == 2) {
					row.createCell(8).setCellValue(searchHitsCountMap.get(key));
					row.createCell(9).setCellValue(searchMap.get(key));
				}
			}
		}

		try (FileOutputStream fileOut = new FileOutputStream(new File(filePath))) {
			workbook.write(fileOut);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
