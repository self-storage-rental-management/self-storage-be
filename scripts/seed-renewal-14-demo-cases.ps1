param([string]$Batch = 'QD261126190000',[string]$Database = 'storagehub_local')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifestPath = Join-Path $root ".local/$Batch-manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing manifest: $manifestPath" }
$rows = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($rows.Count -ne 14) { throw 'Exactly 14 rental fixtures are required.' }
function Q([string]$v) { "'" + $v.Replace("'", "''") + "'" }
function B([string]$v) { "UUID_TO_BIN('$v')" }
$now = [DateTime]::UtcNow
$sql = [Collections.Generic.List[string]]::new()
$sql.Add("SET NAMES utf8mb4; SET time_zone='+00:00'; START TRANSACTION;")
$sql.Add("DELETE FROM payments WHERE idempotency_key LIKE 'demo:GH-TC%';")
$sql.Add("DELETE FROM business_record_codes WHERE type_code='GH' AND display_code LIKE 'GH-TC%';")
$sql.Add("DELETE c FROM business_record_codes c JOIN renewals n ON c.resource_key=CONCAT('GH:',BIN_TO_UUID(n.id)) JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE e FROM renewal_operation_events e JOIN renewals n ON n.id=e.renewal_id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE s FROM renewal_signing_reservations s JOIN renewals n ON n.id=s.renewal_id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE p FROM payments p JOIN renewals n ON n.id=p.renewal_id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE s FROM renewal_operation_states s JOIN renewals n ON n.id=s.id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE w FROM renewal_workflows w JOIN renewals n ON n.id=w.id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE a FROM renewal_accepted_revisions a JOIN renewals n ON n.id=a.renewal_id JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE q FROM renewal_quotes q JOIN rentals r ON r.id=q.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
$sql.Add("DELETE n FROM renewals n JOIN rentals r ON r.id=n.rental_id JOIN users u ON u.id=r.customer_id WHERE u.email='thongvinh2@gmail.com';")
for ($i=0; $i -lt 14; $i++) {
    $caseNo = $i + 1; $r = $rows[$i]
    $renewal=[guid]::NewGuid().ToString(); $quote=[guid]::NewGuid().ToString(); $revision=[guid]::NewGuid().ToString()
    $appointment=[guid]::NewGuid().ToString(); $arrival=[guid]::NewGuid().ToString(); $payment=[guid]::NewGuid().ToString()
    $created=$now.AddDays(-2); $future=$now.AddDays(1).Date.AddHours(3 + ($i % 8)); $start=$future; $end=$future.AddMinutes(30)
    if ($caseNo -eq 7) { $start=$now.AddHours(-2); $end=$start.AddMinutes(30) }
    if ($caseNo -eq 8) { $start=$now.AddMinutes(-30); $end=$start.AddMinutes(30) }
    $paid = $caseNo -notin @(2,3,6)
    $full = $caseNo -in @(1,10,11,14)
    $arrived = $caseNo -in @(1,8,9,10,11,14)
    $completed = $caseNo -in @(1,14)
    $phase = if($completed){'COMPLETED'}elseif($caseNo -eq 3){'PAYMENT_EXPIRED'}elseif($paid){'SIGNING'}else{'AWAITING_PAYMENT_INTENT'}
    $status = if($completed){'completed'}elseif($caseNo -eq 3){'payment_expired'}elseif($full){'full_rent_paid'}elseif($paid){'deposit_paid'}else{'accepted'}
    $deadline = if($caseNo -eq 3){$now.AddHours(-1)}else{$now.AddDays(5)}
    $oldEnd = [DateTime]::ParseExact($r.endDate, 'dd/MM/yyyy', [Globalization.CultureInfo]::InvariantCulture)
    $extensionStart = if ($now.Date -gt $oldEnd.AddDays(1)) { $now.Date } else { $oldEnd.AddDays(1) }
    $extensionEnd = $extensionStart.AddMonths(3)
    $newEnd = $extensionEnd.AddDays(-1)
    $displayCode = "GH-2611-$($caseNo.ToString('000000'))"
    # Keep the accepted snapshot complete enough for the real customer/staff DTO validators.
    # IDs and rental start date are read from the rental row so every fixture remains self-consistent.
    $termsPrefix = "{`"renewalMonths`":3,`"oldEndDate`":`"$($oldEnd.ToString('yyyy-MM-dd'))`",`"extensionStartDate`":`"$($extensionStart.ToString('yyyy-MM-dd'))`",`"extensionEndExclusive`":`"$($extensionEnd.ToString('yyyy-MM-dd'))`",`"newEndDate`":`"$($newEnd.ToString('yyyy-MM-dd'))`",`"unitTypeId`":`""
    $termsAfterType = "`",`"pricingPackageCode`":`"DEMO_3M`",`"packagePolicyRef`":`"66666666-6666-6666-0001-000000000001`",`"packagePolicyVersion`":`"demo-v1`",`"rentalMonths`":3,`"monthlyPrice`":4000000,`"discountRate`":0,`"subtotal`":12000000,`"discountAmount`":0,`"totalAfterDiscount`":12000000,`"renewalDepositAmount`":2400000,`"remainingRentalAmount`":9600000,`"currency`":`"VND`",`"renewalPolicyRef`":`"facility-rental-packages:"
    $termsAfterPolicy = "`",`"renewalPolicyVersion`":`"demo-v1`",`"storageUnitId`":`""
    $termsAfterUnit = "`",`"facilityId`":`""
    $termsAfterFacility = "`",`"endDateConvention`":`"INCLUSIVE`",`"rentalStartDate`":`""
    $termsSuffix = "`",`"paymentPlan`":`"FULL_TERM`",`"signingWindowDays`":3,`"demoCase`":`"GH-TC$($caseNo.ToString('00'))`"}"
    $termsExpr = "CONCAT($(Q $termsPrefix),BIN_TO_UUID(u.unit_type_id),$(Q $termsAfterType),BIN_TO_UUID(r.facility_id),$(Q $termsAfterPolicy),BIN_TO_UUID(r.storage_unit_id),$(Q $termsAfterUnit),BIN_TO_UUID(r.facility_id),$(Q $termsAfterFacility),DATE_FORMAT(r.start_date,'%Y-%m-%d'),$(Q $termsSuffix))"
    $sql.Add("INSERT INTO renewals(id,created_at,updated_at,rental_id,requested_by,status,new_end_date,amount) SELECT $(B $renewal),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),r.id,r.customer_id,'$status','$( $newEnd.ToString('yyyy-MM-dd') )',12000000 FROM rentals r WHERE r.id=$(B $r.rentalId);")
    $sql.Add("INSERT INTO renewal_quotes(id,created_at,updated_at,rental_id,customer_id,terms_json,terms_hash,quoted_at,expires_at) SELECT $(B $quote),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),r.id,r.customer_id,$termsExpr,SHA2('GH-TC$($caseNo.ToString('00'))',256),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY) FROM rentals r JOIN storage_units u ON u.id=r.storage_unit_id WHERE r.id=$(B $r.rentalId);")
    $sql.Add("INSERT INTO renewal_accepted_revisions(id,created_at,updated_at,renewal_id,quote_id,revision_number,accepted_at,note) VALUES($(B $revision),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),$(B $renewal),$(B $quote),1,UTC_TIMESTAMP(6),'LOCAL DEMO GH-TC$($caseNo.ToString('00'))');")
    $paymentDeadline = if($caseNo -eq 3){"DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)"}else{"DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)"}
    $sql.Add("INSERT INTO renewal_workflows(id,version,accepted_revision_id,reviewer_id,reviewed_at,review_reason,payment_deadline,extension_hold_ref,signing_balance_due_at) VALUES($(B $renewal),0,$(B $revision),NULL,NULL,'LOCAL DEMO GH-TC$($caseNo.ToString('00'))',$paymentDeadline,UUID_TO_BIN(UUID()),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 DAY));")
    if($paid){
        $amount=if($full){12000000}else{2400000}; $purpose=if($full){'RENEWAL'}else{'RENEWAL_INSTALLMENT'}
        $sql.Add("INSERT INTO payments(id,created_at,updated_at,amount,currency,idempotency_key,paid_at,processed_at,purpose,status,version,initiated_by,renewal_id,renewal_revision_id,gateway_intent_id,gateway_provider,gateway_transaction_no,gateway_response_code,gateway_transaction_status,refunded_amount,renewal_reconciliation_attempts,invoice_status) SELECT $(B $payment),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),$amount,'VND','demo:GH-TC$($caseNo.ToString('00'))',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'$purpose','PAID',0,r.customer_id,$(B $renewal),$(B $revision),'DEMO-GH-TC$($caseNo.ToString('00'))','MANUAL','GH-TC$($caseNo.ToString('00'))','00','PAID',0,0,'PAID' FROM rentals r WHERE r.id=$(B $r.rentalId);")
    }
    $depositRef=if($paid -and -not $full){B $payment}else{'NULL'}; $fullRef=if($full){B $payment}else{'NULL'}
    $paidAt=if($paid){'UTC_TIMESTAMP(6)'}else{'NULL'}; $arrivalRef=if($arrived){B $arrival}else{'NULL'}; $completedBy=if($completed){"(SELECT id FROM users WHERE email='staff@storagehub.demo' LIMIT 1)"}else{'NULL'}; $completedAt=if($completed){'UTC_TIMESTAMP(6)'}else{'NULL'}
    $pendingException=if($caseNo -eq 11){'UUID_TO_BIN(UUID())'}else{'NULL'}
    $sql.Add("INSERT INTO renewal_operation_states(id,phase,deposit_payment_ref,deposit_paid_at,full_rent_payment_ref,full_rent_paid_at,original_signing_deadline,effective_signing_deadline,recovery_cutoff,appointment_ref,appointment_start,appointment_end,arrival_ref,pending_exception_ref,completed_by,completed_at,policy_ref,policy_version,version) VALUES($(B $renewal),'$phase',$depositRef,$(if($paid -and -not $full){$paidAt}else{'NULL'}),$fullRef,$(if($full){$paidAt}else{'NULL'}),$(Q $deadline.ToString('yyyy-MM-dd HH:mm:ss.fff')),$(Q $deadline.ToString('yyyy-MM-dd HH:mm:ss.fff')),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 DAY),$(B $appointment),$(Q $start.ToString('yyyy-MM-dd HH:mm:ss.fff')),$(Q $end.ToString('yyyy-MM-dd HH:mm:ss.fff')),$arrivalRef,$pendingException,$completedBy,$completedAt,'LOCAL_DEMO','v1',$(if($caseNo -eq 13){'3'}else{'0'}));")
    $sql.Add("INSERT INTO renewal_operation_events(id,created_at,updated_at,renewal_id,kind,occurred_at,actor_id,payload_json) VALUES($(B $appointment),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),$(B $renewal),'APPOINTMENT',UTC_TIMESTAMP(6),NULL,$(Q '{"demo":true}'));")
    if($arrived){$sql.Add("INSERT INTO renewal_operation_events(id,created_at,updated_at,renewal_id,kind,occurred_at,actor_id,payload_json) VALUES($(B $arrival),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),$(B $renewal),'ARRIVAL',UTC_TIMESTAMP(6),NULL,$(Q '{"identityVerified":true}'));")}
    $sql.Add("INSERT INTO business_record_codes(resource_key,type_code,period,display_code,recorded_at) VALUES('GH:$renewal','GH','2611','$displayCode',UTC_TIMESTAMP(6));")
}
$sql.Add('COMMIT;')
$sqlText=$sql -join "`n"
$sqlText | docker exec -i storagehub-mysql sh -c "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD mysql --default-character-set=utf8mb4 -uroot $Database"
if($LASTEXITCODE -ne 0){throw 'Seeding 14 Renewal demo cases failed.'}
Write-Output 'Created GH-2611-000001 through GH-2611-000014.'
