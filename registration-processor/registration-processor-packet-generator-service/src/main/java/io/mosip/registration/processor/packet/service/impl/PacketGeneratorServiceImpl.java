package io.mosip.registration.processor.packet.service.impl;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import com.google.gson.Gson;

import io.mosip.kernel.core.idgenerator.spi.RidGenerator;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.packet.service.PacketCreationService;
import io.mosip.registration.processor.packet.service.PacketGeneratorService;
import io.mosip.registration.processor.packet.service.dto.ErrorDTO;
import io.mosip.registration.processor.packet.service.dto.MachineResponseDto;
import io.mosip.registration.processor.packet.service.dto.PackerGeneratorFailureDto;
import io.mosip.registration.processor.packet.service.dto.PackerGeneratorResDto;
import io.mosip.registration.processor.packet.service.dto.PacketGeneratorDto;
import io.mosip.registration.processor.packet.service.dto.RegistrationCenterResponseDto;
import io.mosip.registration.processor.packet.service.dto.RegistrationDTO;
import io.mosip.registration.processor.packet.service.dto.RegistrationMetaDataDTO;
import io.mosip.registration.processor.packet.service.dto.demographic.DemographicDTO;
import io.mosip.registration.processor.packet.service.dto.demographic.DemographicInfoDTO;
import io.mosip.registration.processor.packet.service.dto.demographic.MoroccoIdentity;
import io.mosip.registration.processor.packet.service.exception.RegBaseCheckedException;
import io.mosip.registration.processor.packet.service.external.StorageService;
import io.mosip.registration.processor.packet.upload.service.SyncUploadEncryptionService;

/**
 * The Class PacketGeneratorServiceImpl.
 */
@Service
public class PacketGeneratorServiceImpl implements PacketGeneratorService {

	/** The rid generator impl. */
	@Autowired
	private RidGenerator<String> ridGeneratorImpl;

	/** The packet creation service. */
	@Autowired
	private PacketCreationService packetCreationService;

	/** The storage service. */
	@Autowired
	private StorageService storageService;

	/** The sync upload encryption service. */
	@Autowired
	SyncUploadEncryptionService syncUploadEncryptionService;

	@Autowired
	private RegistrationProcessorRestClientService<Object> restClientService;

	@Value("${primary.language}")
	private String primaryLanguagecode;

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.registration.processor.packet.service.PacketGeneratorService#
	 * createPacket(io.mosip.registration.processor.packet.service.dto.
	 * PacketGeneratorDto)
	 */
	@Override
	public PackerGeneratorResDto createPacket(PacketGeneratorDto request) {
		// To do master data validation for cetner id and machine id
		PackerGeneratorResDto packerGeneratorResDto = null;
		PackerGeneratorFailureDto dto = new PackerGeneratorFailureDto();

		RegistrationDTO registrationDTO = createRegistrationDTOObject(request.getUin(), request.getRegistrationType(),
				request.getApplicantType(), request.getCenterId(), request.getMachineId());
		byte[] packetZipBytes = null;
		if (isValidCenter(request.getCenterId(), dto) && isValidMachine(request.getMachineId(), dto)) {
			try {
				packetZipBytes = packetCreationService.create(registrationDTO);
				String creationTime = packetCreationService.getCreationTime();
				String filePath = storageService.storeToDisk(registrationDTO.getRegistrationId(), packetZipBytes,
						false);

				File decryptedFile = new File(filePath);

				packerGeneratorResDto = syncUploadEncryptionService.uploadUinPacket(decryptedFile,
						registrationDTO.getRegistrationId(), creationTime);
				return packerGeneratorResDto;
			} catch (RegBaseCheckedException e) {

				dto.setMessage("");
				return dto;
			}
		} else {
			return dto;
		}
	}

	/**
	 * Creates the registration DTO object.
	 *
	 * @param uin
	 *            the uin
	 * @param registrationType
	 *            the registration type
	 * @param applicantType
	 *            the applicant type
	 * @param centerId
	 *            the center id
	 * @param machineId
	 *            the machine id
	 * @return the registration DTO
	 */
	private RegistrationDTO createRegistrationDTOObject(String uin, String registrationType, String applicantType,
			String centerId, String machineId) {
		RegistrationDTO registrationDTO = new RegistrationDTO();
		registrationDTO.setDemographicDTO(getDemographicDTO(uin));
		RegistrationMetaDataDTO registrationMetaDataDTO = getRegistrationMetaDataDTO(registrationType, applicantType,
				uin, centerId, machineId);
		String registrationId = ridGeneratorImpl.generateId(registrationMetaDataDTO.getCenterId(),
				registrationMetaDataDTO.getMachineId());
		registrationDTO.setRegistrationId(registrationId);
		registrationDTO.setRegistrationMetaDataDTO(registrationMetaDataDTO);
		return registrationDTO;

	}

	/**
	 * Gets the demographic DTO.
	 *
	 * @param uin
	 *            the uin
	 * @return the demographic DTO
	 */
	private DemographicDTO getDemographicDTO(String uin) {
		DemographicDTO demographicDTO = new DemographicDTO();
		DemographicInfoDTO demographicInfoDTO = new DemographicInfoDTO();
		MoroccoIdentity identity = new MoroccoIdentity();
		identity.setIdSchemaVersion(1.0);
		identity.setUin(new BigInteger(uin));
		demographicInfoDTO.setIdentity(identity);
		demographicDTO.setDemographicInfoDTO(demographicInfoDTO);
		return demographicDTO;
	}

	/**
	 * Gets the registration meta data DTO.
	 *
	 * @param registrationType
	 *            the registration type
	 * @param applicantType
	 *            the applicant type
	 * @param uin
	 *            the uin
	 * @param centerId
	 *            the center id
	 * @param machineId
	 *            the machine id
	 * @return the registration meta data DTO
	 */
	private RegistrationMetaDataDTO getRegistrationMetaDataDTO(String registrationType, String applicantType,
			String uin, String centerId, String machineId) {
		RegistrationMetaDataDTO registrationMetaDataDTO = new RegistrationMetaDataDTO();

		registrationMetaDataDTO.setApplicationType(applicantType);
		registrationMetaDataDTO.setCenterId(centerId);
		registrationMetaDataDTO.setMachineId(machineId);
		registrationMetaDataDTO.setRegistrationCategory(registrationType);
		registrationMetaDataDTO.setUin(uin);
		return registrationMetaDataDTO;

	}

	private boolean isValidCenter(String centerId, PackerGeneratorFailureDto dto) {
		boolean isValidCenter = false;
		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(centerId);
		pathsegments.add(primaryLanguagecode);
		RegistrationCenterResponseDto rcpdto;
		try {
			rcpdto = (RegistrationCenterResponseDto) restClientService.getApi(ApiName.CENTERDETAILS, pathsegments, "",
					"", RegistrationCenterResponseDto.class);

			if (rcpdto.getErrors() == null && !rcpdto.getRegistrationCenters().isEmpty()) {
				isValidCenter = true;
			} else {
				ErrorDTO error = rcpdto.getErrors().get(0);
				dto.setErrorCode(error.getErrorCode());
				dto.setMessage(error.getErrorMessage());
			}

		} catch (ApisResourceAccessException e) {
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				String result = httpClientException.getResponseBodyAsString();
				Gson gsonObj = new Gson();
				rcpdto = gsonObj.fromJson(result, RegistrationCenterResponseDto.class);
				ErrorDTO error = rcpdto.getErrors().get(0);
				dto.setErrorCode(error.getErrorCode());
				dto.setMessage(error.getErrorMessage());

			}

		}
		return isValidCenter;

	}

	private boolean isValidMachine(String machine, PackerGeneratorFailureDto dto) {
		boolean isValidMachine = false;
		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(machine);
		pathsegments.add(primaryLanguagecode);
		MachineResponseDto machinedto;
		try {
			machinedto = (MachineResponseDto) restClientService.getApi(ApiName.MACHINEDETAILS, pathsegments, "", "",
					MachineResponseDto.class);

			if (machinedto.getErrors() == null && !machinedto.getMachines().isEmpty()) {
				isValidMachine = true;
			} else {
				ErrorDTO error = machinedto.getErrors().get(0);
				dto.setErrorCode(error.getErrorCode());
				dto.setMessage(error.getErrorMessage());
			}

		} catch (ApisResourceAccessException e) {
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				String result = httpClientException.getResponseBodyAsString();
				Gson gsonObj = new Gson();
				machinedto = gsonObj.fromJson(result, MachineResponseDto.class);
				ErrorDTO error = machinedto.getErrors().get(0);
				dto.setErrorCode(error.getErrorCode());
				dto.setMessage(error.getErrorMessage());

			}

		}
		return isValidMachine;

	}
}
