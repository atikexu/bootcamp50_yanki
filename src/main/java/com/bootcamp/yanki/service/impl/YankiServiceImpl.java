package com.bootcamp.yanki.service.impl;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bootcamp.yanki.clients.AccountsRestClient;
import com.bootcamp.yanki.clients.DebitRestClient;
import com.bootcamp.yanki.dto.Account;
import com.bootcamp.yanki.dto.Debit;
import com.bootcamp.yanki.dto.Transaction;
import com.bootcamp.yanki.dto.YankiRequestDto;
import com.bootcamp.yanki.dto.YankiResponseDto;
import com.bootcamp.yanki.entity.Yanki;
import com.bootcamp.yanki.producer.KafkaStringProducer;
import com.bootcamp.yanki.repository.YankiRepository;
import com.bootcamp.yanki.service.YankiService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class YankiServiceImpl implements YankiService{
	@Autowired
	private KafkaStringProducer kafkaStringProducer;
	
    @Autowired
    private YankiRepository yankiRepository;
    
    @Autowired
    private DebitRestClient debitRestClient;
    
    @Autowired
    private AccountsRestClient accountsRestClient;

    @Override
    public Flux<Yanki> getAll() {
        return yankiRepository.findAll();
    }

    @Override
    public Mono<Yanki> getYankiById(String debitId) {
        return yankiRepository.findById(debitId);
    }

    @Override
    public Mono<Yanki> deleteYanki(String debitId) {
        return yankiRepository.findById(debitId)
                .flatMap(debit -> yankiRepository.delete(debit)
                        .then(Mono.just(debit)));
    }
    
    @Override
    public Mono<Yanki> getYankiByTelephone(String telephone){
        return yankiRepository.findAll()
        		.filter(yanki -> yanki.getTelephone().equals(telephone)).next();
    }
    
    @Override
	public Mono<YankiResponseDto> createYanki(YankiRequestDto yankiRequestDto) {
    	
    	Yanki yanki = new Yanki(null, yankiRequestDto.getTypeDocument(), yankiRequestDto.getNumberDocument()
    			, yankiRequestDto.getAmount(), yankiRequestDto.getTelephone(), LocalDateTime.now(), yankiRequestDto.getEmail()
    			, yankiRequestDto.getImei(), yankiRequestDto.getIdDebit(), "YANKI", 0.0);
    	
    	YankiResponseDto response = new YankiResponseDto();
		response.setMessage("Error creating debit card");
		response.setYanki(yanki);
		if(yanki.getIdDebit()!=null) {
			response.setMessage("Debit card does not exist");
			yanki.setAmount(0.0);
			response.setYanki(yanki);
			return debitRestClient.getDebitById(yanki.getIdDebit()).flatMap(deb -> {
				yanki.setStartAmount(deb.getAmount());
				yanki.setAmount(deb.getAmount());
				return yankiRepository.save(yanki).flatMap(w -> {
					response.setMessage("Yanki created");
					return Mono.just(response);
				});
			}).defaultIfEmpty(response);
			
		}else {
			yanki.setAmount(0.0);
			return yankiRepository.save(yanki).flatMap(w -> {
				response.setMessage("Yanki created");
				return Mono.just(response);
			});
		}
	}

	@Override
	public Mono<YankiResponseDto> depositYanki(YankiRequestDto yankiRequestDto) {
		Yanki yanki = new Yanki(yankiRequestDto.getId(), yankiRequestDto.getTypeDocument(), yankiRequestDto.getNumberDocument()
    			, yankiRequestDto.getAmount(), yankiRequestDto.getTelephone(), LocalDateTime.now(), yankiRequestDto.getEmail()
    			, yankiRequestDto.getImei(), yankiRequestDto.getIdDebit(), "YANKI", 0.0);
		YankiResponseDto response = new YankiResponseDto();
		response.setMessage("Yanki not exist");
		response.setYanki(yanki);
		return yankiRepository.findAll().filter(y -> y.getTelephone().equals(yankiRequestDto.getTelephone())).next().flatMap(w -> {
			response.setYanki(w);
			w.setAmount(w.getAmount() + yankiRequestDto.getAmount());
			w.setStartAmount(w.getStartAmount());
			if(w.getIdDebit()!=null) {
				return debitRestClient.getDebitById(w.getIdDebit()).flatMap(deb -> {
					w.setIdDebit(deb.getId());
					deb.setAmount(deb.getAmount() + yankiRequestDto.getAmount());
					return debitRestClient.updateDebit(deb).flatMap(upd -> {
						return accountsRestClient.getAccountById(upd.getAccountId().get(0)).flatMap(account -> {
							account.setAmount(account.getAmount() + yankiRequestDto.getAmount());
							return accountsRestClient.updateAccount(account).flatMap(x -> {
								response.setMessage("successful Deposit");
								return yankiRepository.save(w).flatMap(w1 -> {
									response.setYanki(yanki);
									return registerTransaction(w, yankiRequestDto.getAmount(), "DEPOSITO", x, upd);
								});
							});
						});
					});
				});
			}else {
				return yankiRepository.save(w).flatMap(w1 -> {
					response.setYanki(w1);
					response.setMessage("successful Deposit");
					return registerTransaction(w, yankiRequestDto.getAmount(), "DEPOSITO", null, null);
				});
			}			
		}).defaultIfEmpty(response);
	}
	
	@Override
	public Mono<YankiResponseDto> payYanki(YankiRequestDto yankiRequestDto) {
		Yanki yanki = new Yanki(yankiRequestDto.getId(), yankiRequestDto.getTypeDocument(), yankiRequestDto.getNumberDocument()
    			, yankiRequestDto.getAmount(), yankiRequestDto.getTelephone(), LocalDateTime.now(), yankiRequestDto.getEmail()
    			, yankiRequestDto.getImei(), yankiRequestDto.getIdDebit(), "YANKI", 0.0);
		YankiResponseDto response = new YankiResponseDto();
		response.setMessage("Yanki not exist");
		response.setYanki(yanki);
		return yankiRepository.findAll().filter(y -> y.getTelephone().equals(yankiRequestDto.getTelephone())).next().flatMap(w -> {
			response.setYanki(w);
			w.setStartAmount(w.getStartAmount());
			Double newAmount = w.getAmount() - yankiRequestDto.getAmount();
			if(newAmount<0) {
				response.setMessage("You don't have enough balance");
				return Mono.just(response);
			}
			w.setAmount(w.getAmount() - yankiRequestDto.getAmount());
			if(w.getIdDebit()!=null) {
				return debitRestClient.getDebitById(w.getIdDebit()).flatMap(deb -> {
					w.setIdDebit(deb.getId());
					deb.setAmount(deb.getAmount() - yankiRequestDto.getAmount());
					return debitRestClient.updateDebit(deb).flatMap(upd -> {
						return accountsRestClient.getAccountById(upd.getAccountId().get(0)).flatMap(account -> {
							account.setAmount(account.getAmount() - yankiRequestDto.getAmount());
							return accountsRestClient.updateAccount(account).flatMap(x -> {
								response.setMessage("successful Pay");
								return yankiRepository.save(w).flatMap(w1 -> {
									response.setYanki(w);
									return registerTransaction(w, yankiRequestDto.getAmount(), "RETIRO", x, upd);
								});
							});
						});
					});
				});
			}else {
				return yankiRepository.save(w).flatMap(w1 -> {
					response.setYanki(w);
					response.setMessage("successful Pay");
					return registerTransaction(w, yankiRequestDto.getAmount(), "RETIRO", null, null);
				});
			}			
		}).defaultIfEmpty(response);
	}
	
	private Mono<YankiResponseDto> registerTransaction(Yanki yanki, Double amount, String typeTransaction, Account account, Debit debit){
		Transaction transaction = new Transaction();
		transaction.setCustomerId(yanki.getTelephone());
		transaction.setProductId(yanki.getId());
		transaction.setProductType(yanki.getTypeProduct());
		transaction.setTransactionType(typeTransaction);
		transaction.setAmount(amount);
		transaction.setCustomerType("PERSONAL");
		transaction.setBalance(yanki.getAmount());
		kafkaStringProducer.sendMessage(transaction.toStringObject());
		
		if(yanki.getIdDebit()!=null) {
			transaction.setProductId(account.getId());
			transaction.setCustomerId(account.getCustomerId());
			transaction.setProductType(account.getDescripTypeAccount());
			kafkaStringProducer.sendMessage(transaction.toStringObject());
			transaction.setProductId(debit.getId());
			transaction.setProductType(debit.getProductType());
			kafkaStringProducer.sendMessage(transaction.toStringObject());
		}
		
		return Mono.just(new YankiResponseDto("Successful transaction", yanki));
	}
    
}
