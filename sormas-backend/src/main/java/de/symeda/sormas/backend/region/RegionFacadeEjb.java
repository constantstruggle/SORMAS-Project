package de.symeda.sormas.backend.region;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.enterprise.inject.Specializes;

import de.symeda.sormas.api.region.RegionDataDto;
import de.symeda.sormas.api.region.RegionDto;
import de.symeda.sormas.api.region.RegionFacade;
import de.symeda.sormas.api.region.RegionReferenceDto;
import de.symeda.sormas.backend.util.DtoHelper;

@Stateless(name = "RegionFacade")
public class RegionFacadeEjb implements RegionFacade {

	@EJB
	protected RegionService regionService;
	@EJB
	protected DistrictService districtService;
	@EJB
	protected CommunityService communityService;

	@Override
	public List<RegionReferenceDto> getAllAsReference() {
		return regionService.getAll(Region.NAME, true).stream()
				.map(f -> toReferenceDto(f))
				.collect(Collectors.toList());
	}
	
	@Override
	public List<RegionDto> getAllAfter(Date date) {
		return regionService.getAllAfter(date, null).stream()
			.map(c -> toDto(c))
			.collect(Collectors.toList());
	}
	
	@Override
	public List<RegionDataDto> getAllData() {
		return regionService.getAll(Region.NAME, true).stream()
			.map(c -> toDataDto(c))
			.collect(Collectors.toList());
	}
	
	@Override
	public RegionDto getRegionByUuid(String uuid) {
		return toDto(regionService.getByUuid(uuid));
	}
	
	@Override
	public RegionReferenceDto getRegionReferenceByUuid(String uuid) {
		return toReferenceDto(regionService.getByUuid(uuid));
	}
	
	public static RegionReferenceDto toReferenceDto(Region entity) {
		if (entity == null) {
			return null;
		}
		RegionReferenceDto dto = new RegionReferenceDto();
		DtoHelper.fillReferenceDto(dto, entity);
		return dto;
	}
	
	private RegionDto toDto(Region entity) {
		if (entity == null) {
			return null;
		}
		RegionDto dto = new RegionDto();
		DtoHelper.fillReferenceDto(dto, entity);
		
		dto.setName(entity.getName());
		dto.setEpidCode(entity.getEpidCode());

		return dto;
	}
	
	private RegionDataDto toDataDto(Region entity) {
		if (entity == null) {
			return null;
		}
		RegionDataDto dto = new RegionDataDto();
		DtoHelper.fillDto(dto, entity);
		
		dto.setName(entity.getName());
		dto.setEpidCode(entity.getEpidCode());
		dto.setPopulation(entity.getPopulation());
		dto.setGrowthRate(entity.getGrowthRate());

		return dto;
	}
	
	@LocalBean
	@Stateless
	@Specializes
	public static class RegionFacadeEjbLocal extends RegionFacadeEjb	 {
	}
}
