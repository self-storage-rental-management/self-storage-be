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
      "name": "Kho Nhỏ (S)",
      "lengthM": 5.6,
      "widthM": 6.0,
      "heightM": 3.2,
      "areaM2": 33.6,
      "volumeM3": 107.52,
      "pricePerM3": 51162.04,
      "monthlyPrice": 5500000,
      "maxLoadKg": 600,
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

`availableUnitCount` is the current count of physical units whose status is `available`. It is not a date-range reservation guarantee; date-range availability belongs to the reservation/availability slice.
