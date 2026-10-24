param(
    [Parameter(Mandatory=$true)][string]$CustomerPassword,
    [Parameter(Mandatory=$true)][string]$ManagerPassword,
    [string]$Root = 'http://localhost:8080',
    [string]$BusinessPassword
)
$ErrorActionPreference = 'Stop'
function Login($email, $password) {
    $result = Invoke-RestMethod -Method Post -Uri "$Root/api/auth/login" -ContentType application/json -Body (@{email=$email;password=$password}|ConvertTo-Json)
    return @{Authorization=('Bearer '+$result.data.accessToken)}
}
function Request($method, $path, $headers, $body) {
    $args = @{Method=$method;Uri="$Root$path";Headers=$headers}
    if ($null -ne $body) { $args.ContentType='application/json'; $args.Body=($body|ConvertTo-Json -Depth 12) }
    return (Invoke-RestMethod @args).data
}
$customer=Login 'customer@storagehub.demo' $CustomerPassword
$manager=Login 'manager@storagehub.demo' $ManagerPassword
$previous=Request Get '/api/customer/reservations/844c1efb-3903-432d-b9c9-ff737f2c0e3e' $customer $null
$facilityId=$previous.reservation.facilityId
$unitTypeId=$previous.reservation.unitTypeId
$catalog=Request Get '/api/customer/reservations/goods-catalog' $customer $null
$category=$catalog.categories|Where-Object code -eq TOYS
$material=$category.materials|Where-Object label -eq 'Gỗ'
$packages=Request Get "/api/customer/reservations/rental-packages?facilityId=$facilityId" $customer $null
$package=$packages|Where-Object rentalMonths -eq 3|Select-Object -First 1
$start=(Get-Date).Date.AddDays(1)
$end=$start.AddMonths(3).AddDays(-1)
$item=@{
    category='TOYS'; catalogCategoryId=$category.id; catalogMaterialId=$material.id
    customGoodsName=$null; materialName=$material.label; customMaterial=$null
    description='Kiểm thử mã ngẫu nhiên và lịch sử hàng hóa'; customerNote=$null
    quantity=2; lengthCm=40; widthCm=30; heightCm=30; weightPerItemKg=5; fragile=$false
}
$quote=Request Post '/api/customer/reservations/quote' $customer @{
    facilityId=$facilityId;unitTypeId=$unitTypeId;pricingPackageCode=$package.code
    startDate=$start.ToString('yyyy-MM-dd');endDate=$end.ToString('yyyy-MM-dd')
    goodsCondition='Đã đóng gói'; goodsItems=@($item)
}
$reservation=$null
try {
    # Change the live catalog AFTER quoting. Reservation must keep the old names.
    $null=Request Patch "/api/manager/goods-catalog/categories/$($category.id)" $manager @{label='Tên loại hàng kiểm thử';status='ACTIVE'}
    $null=Request Patch "/api/manager/goods-catalog/materials/$($material.id)" $manager @{label='Tên vật liệu kiểm thử';status='ACTIVE';fragile=$material.fragile}
    $headers=@{}+$customer; $headers['Idempotency-Key']=[guid]::NewGuid().ToString()
    $body=@{quoteId=$quote.quoteId;identityDocumentNumber='012345678901';customerPhone='0901234567';customerAddress='Địa chỉ kiểm thử';goodsCondition='Đã đóng gói';goodsItems=@($item)}
    if (!$body.quoteId) { $body.quoteId=$quote.id }
    $reservation=Request Post '/api/customer/reservations' $headers $body
    if ($reservation.reservationCode -notmatch '^RSV-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$') { throw 'Mã đơn chưa đúng định dạng ngẫu nhiên ngắn' }
    if ($reservation.startDate -ne $quote.startDate -or $reservation.endDate -ne $quote.endDate) { throw 'Ngày quote và reservation khác nhau' }
    $again=Request Post '/api/customer/reservations' $headers $body
    if ($again.id -ne $reservation.id -or $again.reservationCode -ne $reservation.reservationCode) { throw 'Gửi lặp sinh đơn mới' }
    $detail=Request Get "/api/customer/reservations/$($reservation.id)" $customer $null
    $goods=$detail.goodsItems[0]
    if ($goods.categoryName -ne $category.label -or $goods.materialName -ne $material.label -or $goods.catalogMaterialId -ne $material.id) { throw 'Snapshot không giữ nguyên' }
    $null=Request Patch "/api/manager/goods-catalog/materials/$($material.id)" $manager @{label='Tên vật liệu kiểm thử';status='ARCHIVED';fragile=$material.fragile}
    $archived=Request Get "/api/customer/reservations/$($reservation.id)" $customer $null
    if ($archived.goodsItems[0].goodsCode -ne $goods.goodsCode -or $archived.goodsItems[0].categoryName -ne $category.label) { throw 'Lưu trữ danh mục làm đổi lịch sử' }
    [pscustomobject]@{reservationCode=$reservation.reservationCode;goodsCode=$goods.goodsCode;datesMatch=$true;snapshotPreserved=$true;idempotencyPassed=$true;archivePreservesHistory=$true}|ConvertTo-Json
} finally {
    $null=Request Patch "/api/manager/goods-catalog/categories/$($category.id)" $manager @{label=$category.label;status=$category.status}
    $null=Request Patch "/api/manager/goods-catalog/materials/$($material.id)" $manager @{label=$material.label;status=$material.status;fragile=$material.fragile}
    if ($reservation) { $null=Request Post "/api/customer/reservations/$($reservation.id)/cancel" $customer @{reason='Hủy đơn kiểm thử để trả lại sức chứa'} }
}

if ($BusinessPassword) {
    $business=Login 'business@storagehub.demo' $BusinessPassword
    $unit=Request Get "/api/unit-types/$unitTypeId" $manager $null
    $fields=@('code','name','description','lengthM','widthM','heightM','maxLoadKg','rackCount','rackLengthM','rackWidthM','rackHeightM','aisleWidthM','trolleyType','smallBoxCapacity','largeBoxCapacity','imageUrl','detailImageUrl','acceptedGoodsCategoryIds','acceptedGoodsMaterialIds')
    $original=@{}
    foreach ($field in $fields) { $original[$field]=$unit.$field }
    $original.physicalUnitCount=$unit.totalCount
    $newCategory=$null; $newMaterial=$null; $dynamicReservation=$null; $scopeChanged=$false
    function ApproveScope($payload) {
        $null=Request Patch "/api/manager/unit-types/$unitTypeId" $manager $payload
        $changes=Request Get '/api/business/unit-type-changes' $business $null
        $pending=$changes|Where-Object { $_.unitTypeId -eq $unitTypeId -and $_.status -eq 'PENDING' }|Sort-Object createdAt -Descending|Select-Object -First 1
        if (!$pending) { throw 'Không có đề xuất phạm vi chờ duyệt' }
        $null=Request Post "/api/business/unit-type-changes/$($pending.id)/review" $business @{approved=$true;reason='Kiểm thử phạm vi loại hàng';monthlyPrice=$unit.monthlyPrice;securityDepositAmount=$unit.securityDepositAmount}
    }
    try {
        $newCategory=Request Post '/api/manager/goods-catalog/categories' $manager @{label='Loại hàng kiểm thử định danh';status='ACTIVE'}
        $newMaterial=Request Post "/api/manager/goods-catalog/categories/$($newCategory.id)/materials" $manager @{label='Vật liệu kiểm thử định danh';status='ACTIVE';fragile=$false}
        $proposed=@{}+$original
        $proposed.acceptedGoodsCategoryIds=@($original.acceptedGoodsCategoryIds)+@($newCategory.id)
        $proposed.acceptedGoodsMaterialIds=@($original.acceptedGoodsMaterialIds)+@($newMaterial.id)
        ApproveScope $proposed
        $scopeChanged=$true
        $dynamicItem=@{}+$item
        $dynamicItem.category='OTHER'
        $dynamicItem.catalogCategoryId=$newCategory.id
        $dynamicItem.catalogMaterialId=$newMaterial.id
        $dynamicItem.materialName=$newMaterial.label
        $dynamicQuote=Request Post '/api/customer/reservations/quote' $customer @{
            facilityId=$facilityId;unitTypeId=$unitTypeId;pricingPackageCode=$package.code
            startDate=$start.ToString('yyyy-MM-dd');endDate=$end.ToString('yyyy-MM-dd')
            goodsCondition='Đã đóng gói';goodsItems=@($dynamicItem)
        }
        $headers=@{}+$customer; $headers['Idempotency-Key']=[guid]::NewGuid().ToString()
        $quoteId=$dynamicQuote.quoteId; if (!$quoteId) { $quoteId=$dynamicQuote.id }
        $dynamicReservation=Request Post '/api/customer/reservations' $headers @{
            quoteId=$quoteId;identityDocumentNumber='012345678901';customerPhone='0901234567'
            customerAddress='Địa chỉ kiểm thử';goodsCondition='Đã đóng gói';goodsItems=@($dynamicItem)
        }
        $detail=Request Get "/api/customer/reservations/$($dynamicReservation.id)" $customer $null
        if ($detail.goodsItems[0].categoryName -ne $newCategory.label -or $detail.goodsItems[0].requiresStaffReview) { throw 'Loại Manager tạo bị coi là hàng khác' }
        [pscustomobject]@{managerCategoryPassed=$true;reservationCode=$dynamicReservation.reservationCode;categoryCode=$detail.goodsItems[0].categoryCode;materialCode=$detail.goodsItems[0].materialCode}|ConvertTo-Json
    } finally {
        if ($dynamicReservation) { $null=Request Post "/api/customer/reservations/$($dynamicReservation.id)/cancel" $customer @{reason='Hủy đơn kiểm thử'} }
        if ($scopeChanged) { ApproveScope $original }
        if ($newMaterial) { $null=Request Patch "/api/manager/goods-catalog/materials/$($newMaterial.id)" $manager @{label=$newMaterial.label;status='ARCHIVED';fragile=$false} }
        if ($newCategory) { $null=Request Patch "/api/manager/goods-catalog/categories/$($newCategory.id)" $manager @{label=$newCategory.label;status='ARCHIVED'} }
    }
}
