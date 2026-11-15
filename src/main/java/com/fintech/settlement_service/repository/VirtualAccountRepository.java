package com.fintech.settlement_service.repository;

import com.fintech.settlement_service.model.VirtualAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, String> {
}