# Local fixtures only. Never copies gateway signatures or modifies existing business records.
param([string]$Batch = ('QD' + (Get-Date -Format 'yyMMddHHmmss')))
$ErrorActionPreference = 'Stop'
if ($Batch -notmatch '^QD[0-9]{12}$') { throw 'Batch must be QD followed by 12 digits.' }
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
function Query([string]$sql) {
    $result = $sql | docker exec -i storagehub-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql --default-character-set=utf8mb4 -N -B -uroot storagehub_local'
    if ($LASTEXITCODE -ne 0) { throw 'Local database query failed.' }
    return $result
}
function Quoted([string]$value) { return "'" + $value.Replace("'", "''") + "'" }
function Bin([string]$id) { return "UUID_TO_BIN('" + $id + "')" }
$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'SE Asia Standard Time').Date
$now = [DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
$sqlNow = Quoted $now
$templateJson = Query @'
SELECT JSON_OBJECT('rental',BIN_TO_UUID(r.id),'reservation',BIN_TO_UUID(r.reservation_id),
 'unit',BIN_TO_UUID(r.storage_unit_id),'customer',BIN_TO_UUID(r.customer_id),'facility',BIN_TO_UUID(r.facility_id),
 'quote',BIN_TO_UUID(v.source_quote_id),'checkin',BIN_TO_UUID(c.id),'pricing',BIN_TO_UUID(p.id),
 'staff',BIN_TO_UUID(c.performed_by),'unitType',BIN_TO_UUID(v.unit_type_id))
FROM rentals r JOIN reservations v ON v.id=r.reservation_id JOIN check_ins c ON c.rental_id=r.id
JOIN reservation_pricing_snapshots p ON p.reservation_id=v.id JOIN users u ON u.id=r.customer_id
WHERE u.email='customer@storagehub.demo' AND v.status='COMPLETED' AND c.status='completed'
AND NOT EXISTS(SELECT 1 FROM storage_units s WHERE s.id=r.storage_unit_id AND s.code LIKE 'QD%')
ORDER BY r.id LIMIT 1;
'@
if (-not $templateJson) { throw 'A demo customer Rental with completed check-in and pricing is required.' }
$t = $templateJson | ConvertFrom-Json
$target = Query @'
SELECT JSON_OBJECT('facility',BIN_TO_UUID(f.id),'unitType',BIN_TO_UUID(ut.id),'staff',BIN_TO_UUID(u.id))
FROM facilities f JOIN unit_types ut ON ut.facility_id=f.id
JOIN users u ON u.email='staff@storagehub.demo'
JOIN user_facility_scopes s ON s.user_id=u.id AND s.facility_id=f.id
WHERE f.code='HCM-Q1-F01' AND ut.status='active' AND ut.monthly_price=5500000
AND s.scope_level IN ('OPERATE','MANAGE') ORDER BY ut.id LIMIT 1;
'@
if(-not $target){throw 'Demo Q1 facility, 5500000 VND UnitType and scoped Staff are required.'}
$target=$target|ConvertFrom-Json
$t.facility=$target.facility;$t.unitType=$target.unitType;$t.staff=$target.staff
if ([int](Query "SELECT COUNT(*) FROM storage_units WHERE code LIKE '$Batch%';") -ne 0) { throw 'This batch already exists; existing fixtures will not be overwritten.' }
$tables = @('storage_units','reservation_quotes','reservations','rentals','reservation_pricing_snapshots','check_ins')
$columns = @{}
foreach ($table in $tables) {
    $columns[$table] = @(Query "SELECT column_name FROM information_schema.columns WHERE table_schema='storagehub_local' AND table_name='$table' AND extra NOT LIKE '%GENERATED%' ORDER BY ordinal_position;")
}
$sql = [Collections.Generic.List[string]]::new()
$sql.Add("SET NAMES utf8mb4; SET time_zone='+00:00'; START TRANSACTION;")
function Clone([string]$table,[string]$source,[hashtable]$changes) {
    $names = @(); $values = @()
    foreach ($column in $columns[$table]) {
        $names += ('`' + $column + '`')
        if ($changes.ContainsKey($column)) { $values += $changes[$column] }
        elseif ($column -eq 'facility_id') { $values += (Bin $t.facility) }
        elseif ($column -eq 'unit_type_id') { $values += (Bin $t.unitType) }
        elseif ($column -eq 'performed_by') { $values += (Bin $t.staff) }
        elseif ($column -in @('created_at','updated_at')) { $values += $sqlNow }
        else { $values += ('`' + $column + '`') }
    }
    $sql.Add("INSERT INTO $table (" + ($names -join ',') + ') SELECT ' + ($values -join ',') + " FROM $table WHERE id=" + (Bin $source) + ';')
}
$cases = @(
    @('01-CON-HAN',-15,'Chua qua han / TC-01'),
    @('02-GIA-HAN',1,'Canh bao; gia han thanh cong / TC-02,03'),
    @('03-THANH-TOAN',1,'Quay lai VNPay, coc chua ky / TC-04,05'),
    @('04-NGHIEM-TRONG',4,'Nghiem trong; gia han / TC-02,03'),
    @('05-KHAN-CAP',7,'Ngay 7; trang thai phu thuoc gio hien tai / TC-02,06'),
    @('06-THU-THEM',8,'Thu hoi; quyet toan thu them / TC-07..12'),
    @('07-HOAN-TIEN',8,'Thu hoi; quyet toan hoan coc / TC-07..12'),
    @('08-KHONG-CHENH',8,'Thu hoi; quyet toan khong chenh lech / TC-07..12'),
    @('09-TRANH-CHAP',8,'Thu hoi; khach khieu nai / TC-13'),
    @('10-THIEU-BIEN-LAI',8,'Thu hoi; thu chan thieu chung tu / TC-11,12'),
    @('11-GUI-TRUNG',8,'Thu hoi; hai phien thao tac / TC-17,18,19,21'),
    @('12-NGAY-6',6,'Ngay qua han thu 6 / TC-02'),
    @('13-TRA-KHO',1,'Tra kho thuong truoc thu hoi / TC-22')
)
$manifest = @()
Add-Type -AssemblyName System.Drawing
foreach ($case in $cases) {
    $code = "$Batch-$($case[0])"
    $unit=[guid]::NewGuid().ToString(); $quote=[guid]::NewGuid().ToString(); $res=[guid]::NewGuid().ToString()
    $rental=[guid]::NewGuid().ToString(); $pricing=[guid]::NewGuid().ToString(); $checkin=[guid]::NewGuid().ToString()
    $payment=[guid]::NewGuid().ToString(); $file=[guid]::NewGuid().ToString()
    $end=$today.AddDays(-[int]$case[1]); $start=$end.AddDays(1).AddMonths(-3)
    $ds=Quoted $start.ToString('yyyy-MM-dd'); $de=Quoted $end.ToString('yyyy-MM-dd')
    $paid=$start.AddHours(2).ToString('yyyy-MM-dd HH:mm:ss.fff')
    $paidIso=$start.AddHours(2).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
    $bp=Bin $payment; $br=Bin $rental; $bv=Bin $res; $bf=Bin $file
    $bs=Bin $t.staff; $bc=Bin $t.customer; $facility=Bin $t.facility
    Clone 'storage_units' $t.unit @{id=(Bin $unit);code=(Quoted $code);floor="'Demo'";zone="'Kiem thu'";status="'occupied'";available_from='NULL';last_released_at='NULL';version='0'}
    Clone 'reservation_quotes' $t.quote @{id=(Bin $quote);start_date=$ds;end_date=$de;rental_months='3';monthly_price='5500000';subtotal='16500000';discount_rate='0';discount_amount='0';total_after_discount='16500000';reservation_deposit_amount='0';security_deposit_amount='5500000';remaining_rental_amount='16500000';due_at_check_in='22000000';total_initial_obligation='22000000';quoted_at=(Quoted $paid);expires_at=(Quoted $paid);voucher_code='NULL';voucher_discount_amount='0'}
    Clone 'reservations' $t.reservation @{id=$bv;source_quote_id=(Bin $quote);reservation_code=(Quoted ($Batch+'-'+$case[0].Substring(0,2)));idempotency_key=(Quoted ('demo:'+ $res));request_fingerprint='NULL';assigned_unit_id=(Bin $unit);start_date=$ds;end_date=$de;amount='16500000';status="'COMPLETED'";notes=(Quoted ('LOCAL DEMO ONLY - '+$case[2]));version='0';confirmed_at=(Quoted $paid);deposit_paid_at='NULL';appointment_at=(Quoted $paid);hold_expires_at='NULL';payment_expires_at='NULL';complaint_expires_at='NULL'}
    Clone 'rentals' $t.rental @{id=$br;reservation_id=$bv;storage_unit_id=(Bin $unit);start_date=$ds;contract_end_date=$de;status="'active'";actual_returned_at='NULL';completed_at='NULL';monthly_price='5500000'}
    Clone 'reservation_pricing_snapshots' $t.pricing @{id=(Bin $pricing);reservation_id=$bv;rental_months='3';monthly_price='5500000';gross_rental_amount='16500000';discount_rate='0';discount_amount='0';voucher_code='NULL';voucher_discount_type='NULL';voucher_discount_value='NULL';voucher_discount_amount='0';net_rental_amount='16500000';reservation_deposit_amount='0';security_deposit_amount='5500000';remaining_rental_amount='16500000';due_at_check_in='22000000';total_initial_obligation='22000000';quoted_at=(Quoted $paid);expires_at=(Quoted $paid)}
    Clone 'check_ins' $t.checkin @{id=(Bin $checkin);reservation_id=$bv;rental_id=$br;status="'completed'";scheduled_at=(Quoted $paid);checked_in_at=(Quoted $paid);readiness_note="'LOCAL DEMO ONLY'";version='0';checklist_json="'{}'"}
    $sql.Add("INSERT INTO contracts(id,created_at,updated_at,rental_id,status,contract_number,signed_at,signed_by) VALUES(UUID_TO_BIN(UUID()),$sqlNow,$sqlNow,$br,'SIGNED','HD-$code',$(Quoted $paid),'KHACH DEMO');")
    # Synthetic local cash receipt, visibly labelled; never an authenticated gateway callback.
    $path=Join-Path $root "uploads/$file.png"
    $bitmap=[Drawing.Bitmap]::new(1000,320); $g=[Drawing.Graphics]::FromImage($bitmap)
    $g.Clear([Drawing.Color]::White); $font=[Drawing.Font]::new('Arial',19)
    $g.DrawString("LOCAL DEMO - KHONG CO GIA TRI THU TIEN`n$code`nBien lai gia lap: 22.000.000 VND`nTien thue 16.500.000 + coc bao dam 5.500.000`nChi dung kiem thu, khong phai giao dich thuc.",$font,[Drawing.Brushes]::Black,20,25)
    $bitmap.Save($path,[Drawing.Imaging.ImageFormat]::Png);$g.Dispose();$font.Dispose();$bitmap.Dispose()
    $size=(Get-Item -LiteralPath $path).Length; $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $storagePath=Quoted ($path.Replace('\','/'))
    $sql.Add("INSERT INTO file_assets(id,created_at,updated_at,uploaded_by,storage_key,original_name,content_type,size_bytes,checksum_sha256,entity_type,entity_id,status) VALUES($bf,$sqlNow,$sqlNow,$bs,$storagePath,'DEMO-bien-lai-$code.png','image/png',$size,'$hash','CHECK_IN',$(Bin $checkin),'ACTIVE');")
    $sql.Add("INSERT INTO payments(id,created_at,updated_at,initiated_by,reservation_id,amount,currency,purpose,status,idempotency_key,processed_at,paid_at,gateway_intent_id,gateway_provider,gateway_transaction_no,gateway_bank_code,gateway_response_code,gateway_transaction_status,version,refunded_amount) VALUES($bp,$sqlNow,$sqlNow,$bs,$bv,22000000,'VND','RENTAL_BALANCE','PAID','demo:$payment',$(Quoted $paid),$(Quoted $paid),'MANUAL-DEMO-$payment','MANUAL','DEMO-$payment','CASH','00','PAID',0,0);")
    $proof=@{paymentId=$payment;reservationId=$res;renewalId=$null;acceptedRevisionId=$null;amount=22000000;currency='VND';paymentMethod='CASH';transactionReference="DEMO-$payment";initiatedBy=$t.staff;paidAt=$paidIso;receiptFileId=$file;localDemo=$true;batch=$Batch}|ConvertTo-Json -Compress
    $sql.Add("INSERT INTO activity_logs(id,created_at,updated_at,actor_id,facility_id,action,entity_type,entity_id,after_state_json,correlation_id) VALUES(UUID_TO_BIN(UUID()),$sqlNow,$sqlNow,$bs,$facility,'MANUAL_RENTAL_BALANCE_RECORDED_V1','Payment',$bp,$(Quoted $proof),'$Batch');")
    $sql.Add("INSERT INTO confirmed_rental_periods(rental_id,reservation_id,customer_id,facility_id,storage_unit_id,start_date,stored_end_date,end_convention,source_ref,confirmed_at) VALUES('$rental','$res','$($t.customer)','$($t.facility)','$unit',$ds,$de,'INCLUSIVE','local-demo:${Batch}:$rental',$sqlNow);")
    $sql.Add("INSERT INTO rental_ledger_accounts(rental_id,customer_id,facility_id,revision) VALUES('$rental','$($t.customer)','$($t.facility)',0);")
    $fixture=@{localDemo=$true;batch=$Batch;rentalId=$rental;reservationId=$res;unitCode=$code;scenario=$case[2]}|ConvertTo-Json -Compress
    $sql.Add("INSERT INTO activity_logs(id,created_at,updated_at,actor_id,facility_id,action,entity_type,entity_id,after_state_json,correlation_id) VALUES(UUID_TO_BIN(UUID()),$sqlNow,$sqlNow,$bs,$facility,'LOCAL_OVERDUE_DEMO_CREATED','Rental',$br,$(Quoted $fixture),'$Batch');")
    $manifest += [pscustomobject]@{code=$code;rentalId=$rental;reservationId=$res;endDate=$end.ToString('dd/MM/yyyy');scenario=$case[2];receiptFileId=$file}
}
$sql.Add('COMMIT;')
$local=Join-Path $root '.local'
$sampleDirectory=Join-Path $local 'overdue-demo-evidence'
New-Item -ItemType Directory -Path $sampleDirectory -Force | Out-Null
foreach($name in @('bien-ban-kiem-ke-demo','bien-lai-quyet-toan-demo','bien-ban-hoan-tat-demo')) {
    $image=[Drawing.Bitmap]::new(1000,260); $graphics=[Drawing.Graphics]::FromImage($image)
    $graphics.Clear([Drawing.Color]::White); $sampleFont=[Drawing.Font]::new('Arial',18)
    $graphics.DrawString("TAI LIEU GIA LAP - CHI DUNG KIEM THU`n$name`nLo du lieu: $Batch`nSo tien va ket qua thao tac: doi chieu tren form web.`nKhong phai chung tu thuc thu hoac hoan tien.",$sampleFont,[Drawing.Brushes]::Black,20,20)
    $image.Save((Join-Path $sampleDirectory "$name.png"),[Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose();$sampleFont.Dispose();$image.Dispose()
}
$backup=Join-Path $local "$Batch-before.sql"
docker exec storagehub-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysqldump -uroot --hex-blob --single-transaction --routines --triggers --no-tablespaces storagehub_local > /tmp/storagehub-before-demo.sql'
if ($LASTEXITCODE -ne 0) { throw 'Backup failed; fixtures were not inserted.' }
docker cp storagehub-mysql:/tmp/storagehub-before-demo.sql $backup
if($LASTEXITCODE -ne 0){throw 'Backup copy failed; fixtures were not inserted.'}
$sqlPath=Join-Path $local "$Batch-fixtures.sql"
[IO.File]::WriteAllText($sqlPath,($sql -join "`n"),[Text.UTF8Encoding]::new($false))
Query ($sql -join "`n") | Out-Null
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $local "$Batch-manifest.json") -Encoding UTF8
Write-Output "Created $($manifest.Count) local demo Rentals; batch $Batch."
$manifest | Format-Table code,endDate,rentalId -AutoSize
