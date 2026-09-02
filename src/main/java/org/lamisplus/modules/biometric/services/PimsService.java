package org.lamisplus.modules.biometric.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lamisplus.modules.base.controller.apierror.EntityNotFoundException;
import org.lamisplus.modules.base.domain.entities.OrganisationUnitIdentifier;
import org.lamisplus.modules.base.domain.repositories.OrganisationUnitIdentifierRepository;
import org.lamisplus.modules.biometric.domain.PimsConfig;
import org.lamisplus.modules.biometric.domain.PimsTracker;
import org.lamisplus.modules.biometric.domain.dto.*;
import org.lamisplus.modules.biometric.repository.PimsConfigRepository;
import org.lamisplus.modules.biometric.repository.PimsTrackerRepository;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PimsService {
	
	//public static final String HTTP_STAGEDEMO_PHIS_3_PROJECT_ORG_NG_PIMS = "http://stagedemo.phis3project.org.ng/pims";
	//public static final String HTTP_STAGEDEMO_PHIS_3_PROJECT_ORG_NG_PIMS = "http://pimssandbox.phis3project.org.ng/api";

	public static final String HTTP_STAGEDEMO_PHIS_3_PROJECT_ORG_NG_PIMS = "*****";
	private final PimsTrackerRepository pimsTrackerRepository;
	private final PimsConfigRepository pimsConfigRepository;
	
	private final OrganisationUnitIdentifierRepository identifierRepository;
	
	
	public PimsConfig registerPimsConfig(String username, String password, String url){
		return  pimsConfigRepository.save(new PimsConfig(username, password, url));
	}
	
	public PimsConfig updatePimsConfig(Long id, PimsConfig pimsConfig){
		PimsConfig pimConfig = pimsConfigRepository
				.findById(id)
				.orElseThrow(() -> new EntityNotFoundException(PimsConfig.class, "id", id + " not found"));
		pimConfig.setUrl(pimsConfig.getUrl());
		pimConfig.setUsername(pimsConfig.getUsername());
		pimConfig.setPassword(pimsConfig.getPassword());
		return  pimsConfigRepository.save(pimConfig);
	}
	
	public List<PimsTracker> getAllPimsVerification(){
		return  pimsTrackerRepository.findAll();
	}
	public List<PimsTracker> getPassedPimsVerification(){
		return  pimsTrackerRepository.findAllByVerification(Boolean.TRUE);
	}

	public List<PimsTracker> getFailedPimsVerification(){
		return  pimsTrackerRepository.findAllByVerification(Boolean.FALSE);
	}

	public List<PimsConfig> getPimConfigs(){
		return  pimsConfigRepository.findAll();
	}
	
	public Object verifyPatientFromPins(Long facilityId,String patientId, PimsRequestDTO pimsRequestDTO) {
		log.info("id {}", patientId);
		log.info("facility {}", facilityId);
		ObjectMapper mapper = new ObjectMapper();
		PimsVerificationResponseDTO pimsVerificationResponseDTO = patientISAlreadyPIMSVerified(facilityId, patientId,mapper);
		if(pimsVerificationResponseDTO != null){
			return pimsVerificationResponseDTO;
		}
		Optional<OrganisationUnitIdentifier> datimIdOptional = identifierRepository.findAll()
				.stream()
				.filter(i -> facilityId.equals(i.getOrganisationUnitId()) && "DATIM_ID".equals(i.getName()))
				.findAny();
		if(datimIdOptional.isPresent()){
			OrganisationUnitIdentifier organisationUnitIdentifier = datimIdOptional.get();
			pimsRequestDTO.setFacilityId(organisationUnitIdentifier.getCode());
			log.info("datim code {}", organisationUnitIdentifier.getCode());
		}
		RestTemplate restTemplate = new RestTemplate();
		//String url = "http://pimssandbox.phis3project.org.ng/api/Prints/findClient";

		String url = "https://pimssandbox.phis3project.org.ng/api/Prints/findClient";
		PimsAuthenticationResponse pimsAuthentication = getPimsAuthentication(restTemplate);
		Optional<PimsConfig> config = pimsConfigRepository.findFirstByArchived(0);
		if(config.isPresent()){
			log.info("dynamic configuration");
			url = config.get().getUrl()+"/Prints/findClient";
		}
			if (pimsAuthentication != null && "true".equalsIgnoreCase(pimsAuthentication.getIsAuthenticated())) {
				String token = pimsAuthentication.getToken();
				log.info("token: " + token);
				HttpHeaders headers = GetHTTPHeaders();
				headers.add("Authorization","Bearer "+token);
				PimsOnlineRequestDTO request = new PimsOnlineRequestDTO();
				request.setIndex(pimsRequestDTO.getIndex());
				request.setFacilityId(pimsRequestDTO.getFacilityId());
				request.setFinger(Base64.getEncoder().encodeToString(pimsRequestDTO.getFinger()));
				HttpEntity<PimsOnlineRequestDTO> requestDTOEntity = new HttpEntity<>(request, headers);
				ResponseEntity<PimsVerificationResponseDTO> responseEntity =
						getRestTemplate(restTemplate).exchange(url, HttpMethod.POST, requestDTOEntity, PimsVerificationResponseDTO.class);
				PimsVerificationResponseDTO response = responseEntity.getBody();
				log.info("verify Response: " + response);
				if (response == null) {
					log.error("PIMS returned an empty verification response for patient {}", patientId);
					return null;
				}
				saveVerificationOnLocalSystem(facilityId, patientId, mapper, response);
				return response;
			}else {
				log.error("Failed authentication from PIMS server, kindly ensure you had valid credentials");
				return  pimsAuthentication;
			}
	}
	
	private void saveVerificationOnLocalSystem(Long facilityId, String patientId, ObjectMapper mapper, PimsVerificationResponseDTO response) {
		JsonNode jsonNodeResponse = mapper.valueToTree(response);
		log.info("saving Response on system ");
		String pimPatientId = null;
		if(response.getEnrollments() != null && !response.getEnrollments().isEmpty()){
			pimPatientId = response.getEnrollments().get(0).getPatientId();
		}
		boolean verified = response.getMessage() != null && response.getMessage().contains("success");
		Optional<PimsTracker> pimsTrackerOptional =
				pimsTrackerRepository.getPimsTrackerByPersonUuidAndFacilityIdAndArchived(patientId, facilityId,0);
		if(pimsTrackerOptional.isPresent()){
			PimsTracker pimsTracker = pimsTrackerOptional.get();
			pimsTracker.setArchived(0);
			pimsTracker.setData(jsonNodeResponse);
			pimsTracker.setDate(LocalDate.now());
			pimsTracker.setPimsPatientId(pimPatientId);
			pimsTracker.setIsVerified(verified);
			pimsTrackerRepository.save(pimsTracker);
			log.info("updated successfully");
		}else {
			PimsTracker pimsTracker = PimsTracker.builder()
					.isVerified(verified)
					.facilityId(facilityId)
					.data(jsonNodeResponse)
					.pimsPatientId(pimPatientId)
					.personUuid(patientId)
					.archived(0)
					.date(LocalDate.now())
					.build();
			pimsTrackerRepository.save(pimsTracker);
			log.info("save successfully");
		}
	}
	
	private PimsVerificationResponseDTO patientISAlreadyPIMSVerified(Long facilityId, String patientId, ObjectMapper mapper) {
		try {
			if (patientId != null) {
				log.info("An already existed verified patient " );
				Optional<PimsTracker> pimsTrackerOptional =
						pimsTrackerRepository.getPimsTrackerByPersonUuidAndFacilityIdAndArchived(patientId, facilityId,0);
				if (pimsTrackerOptional.isPresent()) {
					PimsTracker pimsTracker = pimsTrackerOptional.get();
					log.info("data {}",  pimsTracker.toString());
					if (Boolean.TRUE.equals(pimsTracker.getIsVerified())) {
						JsonNode data = pimsTracker.getData();
						return mapper.treeToValue(data, PimsVerificationResponseDTO.class);
					}
				}
			}
		}catch(Exception e){
		  log.error("An error occur during  checking a patient in the DB error message {} ", Arrays.toString(e.getStackTrace()) );
		}
		return null;
	}
	
	public PimsAuthenticationResponse getPimsAuthentication(RestTemplate restTemplate) {
		try {
			Optional<PimsConfig> config = pimsConfigRepository.findFirstByArchived(0);
			String url = "https://pimssandbox.phis3project.org.ng/api/auth/token";
			PimsUserCredentials userCredentials = null;
			if(config.isPresent()){
				PimsConfig pimsConfig = config.get();
				url = pimsConfig.getUrl()+"/auth/token";
				log.info("dynamic coded configuration");
				userCredentials = new PimsUserCredentials(pimsConfig.getUsername(), pimsConfig.getPassword());
				
			}else{
				 userCredentials = new PimsUserCredentials("******", "******");
				log.info("payload: " + userCredentials.toString());
			}
			HttpEntity<PimsUserCredentials> loginEntity = new HttpEntity<>(userCredentials, GetHTTPHeaders());
			ResponseEntity<PimsAuthenticationResponse> responseEntity =
					getRestTemplate(restTemplate).exchange(url, HttpMethod.POST, loginEntity, PimsAuthenticationResponse.class);
			log.info("auth response {}", responseEntity.getBody());
			return responseEntity.getBody();
		}catch (Exception e) {
			log.error("Could not authenticate against the PIMS server", e);
		}
		return null;
	}
	
	private RestTemplate getRestTemplate(RestTemplate restTemplate) {
		List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();
		MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
		converter.setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
		messageConverters.add(converter);
		restTemplate.setMessageConverters(messageConverters);
		return restTemplate;
	}
	
	private HttpHeaders GetHTTPHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.add("user-agent", "Application");
		return headers;
	}
	
//	public String bcryptHash(byte[] template) {
//		String encoded = Base64.getEncoder().encodeToString(template);
//		return BCrypt.hashpw(encoded, "$2a$12$MklNDNgs4Agd50cSasj91O");
//	}

}
