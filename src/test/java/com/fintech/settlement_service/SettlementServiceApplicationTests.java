package com.fintech.settlement_service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.consumer.SettlementConsumer;
import com.fintech.settlement_service.controller.SettlementWebhookController;
import com.fintech.settlement_service.dto.SettlementEvent;
import com.fintech.settlement_service.model.*;
import com.fintech.settlement_service.repository.*;
import com.fintech.settlement_service.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SettlementServiceApplicationTests {
  private final ObjectMapper mapper = new ObjectMapper();
  private TransactionTemplate tx() { var tm = mock(PlatformTransactionManager.class); when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); return new TransactionTemplate(tm); }
  @Test void feeClampsCeilingAndCap() {
    var svc = new VirtualAccountRegistryService(mock(VirtualAccountRepository.class), mock(StringRedisTemplate.class));
    var std = new VirtualAccountRegistryService.AccountTier("0123456789","M1","A",150,200000L,true);
    assertEquals(15000L, svc.calculateFeeKobo(1_000_000L, std));
    assertEquals(200000L, svc.calculateFeeKobo(50_000_000L, std));
    var over = new VirtualAccountRegistryService.AccountTier("0123456789","M1","A",25000,99_999_999L,true);
    assertEquals(1_000_000L, svc.calculateFeeKobo(1_000_000L, over));
  }
  @Test void virtualAccountRejectsDuplicateAndUnregistered() {
    var vaRepo = mock(VirtualAccountRepository.class); var redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    when(vaRepo.existsById("0123456789")).thenReturn(true);
    var svc = new VirtualAccountRegistryService(vaRepo, redis);
    assertThrows(VirtualAccountRegistryService.DuplicateAccountException.class, () -> svc.registerAccount("0123456789","M2","X",100,50000L));
    when(vaRepo.findById("9999999999")).thenReturn(Optional.empty());
    assertThrows(VirtualAccountRegistryService.UnregisteredAccountException.class, () -> svc.resolveAccountTier("9999999999","M2"));
  }
  @Test void consumerIdempotencySkipsDuplicateCredit() throws Exception {
    var led = mock(SettlementLedgerRepository.class); var wal = mock(MerchantWalletRepository.class);
    var vaRepo = mock(VirtualAccountRepository.class); var redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    when(vaRepo.findById("0123456789")).thenReturn(Optional.of(new VirtualAccount("0123456789","M1","Core",150,200000L,true)));
    when(led.insertLedgerEntryIdempotently(any(),any(),any(),anyLong(),anyLong(),anyLong(),any())).thenReturn(0);
    var consumer = new SettlementConsumer(led, wal, mock(DeadLetterEventRepository.class), new VirtualAccountRegistryService(vaRepo, redis));
    var ev = mapper.readValue(mapper.writeValueAsString(Map.of("transactionReference","T1","virtualAccountNumber","0123456789","amountKobo",1000000,"merchantId","M1")), SettlementEvent.class);
    consumer.consumeSettlement(ev); verify(wal, never()).creditWalletAtomically(any(), anyLong());
  }
  @Test void payoutGuardsCollisionTimeoutAndFailure() {
    var po = mock(MerchantPayoutRepository.class); var wal = mock(MerchantWalletRepository.class); var led = mock(SettlementLedgerRepository.class);
    var svc = new PayoutService(po, wal, led, tx());
    when(po.insertPayoutIdempotently(any(),any(),any(),any(),anyLong())).thenReturn(1);
    when(wal.debitWalletIfSufficient(any(),anyLong())).thenReturn(1);
    when(led.insertLedgerEntryIdempotently(eq("COL1"),any(),any(),anyLong(),anyLong(),anyLong(),any())).thenReturn(0);
    assertEquals(409, svc.executeDisbursement(new PayoutService.PayoutRequest("COL1","M1","058","1234567890",50000L)).httpStatus());
    when(led.insertLedgerEntryIdempotently(eq("TSQ1"),any(),any(),anyLong(),anyLong(),anyLong(),any())).thenReturn(1);
    assertEquals(202, svc.executeDisbursement(new PayoutService.PayoutRequest("TSQ1","M1","998","1234567890",50000L)).httpStatus());
    verify(wal, never()).creditWalletAtomically(any(), anyLong());
    when(led.insertLedgerEntryIdempotently(eq("FAIL1"),any(),any(),anyLong(),anyLong(),anyLong(),any())).thenReturn(1);
    when(led.insertLedgerEntryIdempotently(eq("FAIL1-REV"),any(),any(),anyLong(),anyLong(),anyLong(),any())).thenReturn(1);
    assertEquals(502, svc.executeDisbursement(new PayoutService.PayoutRequest("FAIL1","M1","999","0000000000",50000L)).httpStatus());
    verify(wal, times(1)).creditWalletAtomically("M1", 50000L);
  }
  @Test void reversalRejectsNonSettledRows() throws Exception {
    var led = mock(SettlementLedgerRepository.class); var wal = mock(MerchantWalletRepository.class);
    var sec = new WebhookSecurityService(); ReflectionTestUtils.setField(sec,"secretKey","sec"); ReflectionTestUtils.setField(sec,"enforceSignature",true);
    var ctrl = new SettlementWebhookController(mock(OutboxEventRepository.class), led, wal, sec);
    var row = mock(SettlementLedger.class); when(row.getStatus()).thenReturn("PAYOUT_DEBIT");
    when(led.findByTransactionReference("PAY1")).thenReturn(Optional.of(row));
    String body = mapper.writeValueAsString(Map.of("reversalReference","REV1","originalTransactionReference","PAY1"));
    assertEquals(409, ctrl.handleReversal(sec.computeSignature(body), body).getStatusCode().value());
  }
}