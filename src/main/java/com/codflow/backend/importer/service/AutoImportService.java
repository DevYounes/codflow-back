package com.codflow.backend.importer.service;

import com.codflow.backend.config.service.SystemSettingService;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.dto.CreateOrderRequest;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Watches a configured Excel file and imports new rows automatically.
 *
 * The file path is stored in SystemSetting under key "excel.import.file.path".
 * Last-modified timestamp is tracked to avoid re-scanning an unchanged file.
 * Row idempotency is guaranteed by the existing orderNumber uniqueness check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoImportService {

    private static final String KEY_LAST_MODIFIED = "excel.import.last_modified";

    private final SystemSettingService settingService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    // Column indexes (same mapping as ExcelImportService)
    private static final int COL_ORDER_NUMBER  = 0;
    private static final int COL_CUSTOMER_NAME = 1;
    private static final int COL_PHONE         = 2;
    private static final int COL_PHONE2        = 3;
    private static final int COL_ADDRESS       = 4;
    private static final int COL_CITY          = 5;
    private static final int COL_VILLE         = 6;
    private static final int COL_PRODUCT_NAME  = 7;
    private static final int COL_PRODUCT_SKU   = 8;
    private static final int COL_QUANTITY      = 9;
    private static final int COL_UNIT_PRICE    = 10;
    private static final int COL_SHIPPING_COST = 11;
    private static final int COL_NOTES         = 12;

    /**
     * Runs every minute. Checks if the configured Excel file has changed,
     * then imports any new rows.
     */
    @Scheduled(fixedDelayString = "${app.import.check-interval-ms:60000}")
    public void checkAndImport() {
        String filePath = settingService.get(SystemSettingService.KEY_EXCEL_IMPORT_PATH).orElse(null);
        if (filePath == null || filePath.isBlank()) {
            return; // not configured
        }

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("Auto-import: configured file not found: {}", filePath);
            return;
        }

        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            String storedModified = settingService.get(KEY_LAST_MODIFIED).orElse(null);

            if (storedModified != null && storedModified.equals(lastModified.toString())) {
                return; // file hasn't changed
            }

            log.info("Auto-import: file changed, importing from {}", filePath);
            ImportResultDto result = importFromFile(path);
            log.info("Auto-import result: {} imported, {} skipped, {} errors",
                    result.getImported(), result.getSkipped(), result.getErrors());

            settingService.set(KEY_LAST_MODIFIED, lastModified.toString());

        } catch (IOException e) {
            log.error("Auto-import: error reading file {}: {}", filePath, e.getMessage());
        }
    }

    @Transactional
    public ImportResultDto importFromFile(Path path) {
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int imported = 0;
        int totalRows = 0;

        try (FileInputStream fis = new FileInputStream(path.toFile());
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (rowIterator.hasNext()) rowIterator.next(); // skip header

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                totalRows++;
                if (isRowEmpty(row)) continue;

                try {
                    CreateOrderRequest request = parseRow(row);
                    String orderNum = request.getOrderNumber();
                    if (orderNum != null && orderRepository.existsByOrderNumber(orderNum)) {
                        skipped.add("Ligne " + (row.getRowNum() + 1) + ": " + orderNum + " déjà importée");
                        continue;
                    }
                    orderService.createOrder(request, null);
                    imported++;
                } catch (Exception e) {
                    errors.add("Ligne " + (row.getRowNum() + 1) + ": " + e.getMessage());
                    log.warn("Auto-import row {}: {}", row.getRowNum() + 1, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Impossible de lire le fichier: " + e.getMessage(), e);
        }

        return ImportResultDto.builder()
                .totalRows(totalRows)
                .imported(imported)
                .skipped(skipped.size())
                .errors(errors.size())
                .errorMessages(errors)
                .skippedMessages(skipped)
                .build();
    }

    private CreateOrderRequest parseRow(Row row) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setSource(OrderSource.EXCEL);

        String customerName = getCellString(row, COL_CUSTOMER_NAME);
        String phone        = getCellString(row, COL_PHONE);
        String address      = getCellString(row, COL_ADDRESS);
        String city         = getCellString(row, COL_CITY);

        if (customerName == null || customerName.isBlank()) throw new IllegalArgumentException("Nom client manquant");
        if (phone == null || phone.isBlank())               throw new IllegalArgumentException("Téléphone manquant");
        if (address == null || address.isBlank())           throw new IllegalArgumentException("Adresse manquante");
        if (city == null || city.isBlank())                 throw new IllegalArgumentException("Ville manquante");

        request.setCustomerName(customerName.trim());
        request.setCustomerPhone(phone.trim());
        request.setCustomerPhone2(getCellString(row, COL_PHONE2));
        request.setAddress(address.trim());
        request.setCity(city.trim());
        request.setVille(getCellString(row, COL_VILLE));
        request.setNotes(getCellString(row, COL_NOTES));
        request.setOrderNumber(getCellString(row, COL_ORDER_NUMBER));

        BigDecimal shippingCost = getCellBigDecimal(row, COL_SHIPPING_COST);
        request.setShippingCost(shippingCost != null ? shippingCost : BigDecimal.ZERO);

        String productName = getCellString(row, COL_PRODUCT_NAME);
        if (productName == null || productName.isBlank()) throw new IllegalArgumentException("Nom produit manquant");

        BigDecimal unitPrice = getCellBigDecimal(row, COL_UNIT_PRICE);
        if (unitPrice == null) throw new IllegalArgumentException("Prix unitaire manquant");

        int quantity = getCellInt(row, COL_QUANTITY, 1);

        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setProductName(productName.trim());
        item.setProductSku(getCellString(row, COL_PRODUCT_SKU));
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);

        String sku = getCellString(row, COL_PRODUCT_SKU);
        if (sku != null && !sku.isBlank()) {
            productRepository.findBySku(sku.trim()).ifPresent(p -> item.setProductId(p.getId()));
        }

        request.setItems(List.of(item));
        return request;
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int i = COL_CUSTOMER_NAME; i <= COL_UNIT_PRICE; i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellString(row, i);
                if (val != null && !val.isBlank()) return false;
            }
        }
        return true;
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) yield cell.getLocalDateTimeCellValue().toString();
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val)) yield String.valueOf((long) val);
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING
                    ? cell.getStringCellValue()
                    : String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }

    private BigDecimal getCellBigDecimal(Row row, int col) {
        String val = getCellString(row, col);
        if (val == null || val.isBlank()) return null;
        try {
            return new BigDecimal(val.replace(",", ".").replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int getCellInt(Row row, int col, int defaultValue) {
        BigDecimal val = getCellBigDecimal(row, col);
        return val != null ? val.intValue() : defaultValue;
    }
}
