# Customer catalog API

This is the first customer-facing catalog slice mapped from `fe/src/data/demoDatabase.ts` and `fe/src/types/storageHub.ts`.

## List unit types of a facility

`GET /api/facilities/{facilityId}/unit-types`

Authentication is required. A Staff or Manager actor must have read scope for the facility. Customer, Business, and Admin actors may read facilities allowed by their role.

Supported query parameters:

- `page`: zero-based page number.
- `size`: page size, using Spring Data pagination.
- `sort`: for example `monthlyPrice,asc`.
- `status`: `active` or `inactive`.

Example response:

```json
{
  "data": [
    {
      "id": "uuid",
      "facilityId": "uuid",
      "code": "S",
      "name": "Kho Nhỏ (S)",
      "lengthM": 5.6,
      "widthM": 6.0,
      "heightM": 3.2,
      "areaM2": 33.6,
      "volumeM3": 107.52,
      "monthlyPrice": 5500000,
      "maxLoadKg": 600,
      "rackCount": 4,
      "rackLengthM": 2.0,
      "rackWidthM": 4.0,
      "rackHeightM": 4.5,
      "status": "active",
      "availableUnitCount": 5,
      "createdAt": "2026-09-27T00:00:00Z",
      "updatedAt": "2026-09-27T00:00:00Z"
    }
  ],
  "pagination": {
    "page": 0,
    "pageSize": 20,
    "totalItems": 4,
    "totalPages": 1,
    "sort": "monthlyPrice: ASC"
  },
  "correlationId": "request-id"
}
```

`areaM2` và `volumeM3` do BE tính từ ba kích thước; không lưu như dữ liệu nhập độc lập. `availableUnitCount` là số physical unit hiện có status `available`, chưa phải bảo đảm availability theo khoảng ngày.
