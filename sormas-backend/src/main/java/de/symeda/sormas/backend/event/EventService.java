/*******************************************************************************
 * SORMAS® - Surveillance Outbreak Response Management & Analysis System
 * Copyright © 2016-2018 Helmholtz-Zentrum für Infektionsforschung GmbH (HZI)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/
package de.symeda.sormas.backend.event;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.apache.commons.lang3.StringUtils;

import de.symeda.sormas.api.Disease;
import de.symeda.sormas.api.EntityRelevanceStatus;
import de.symeda.sormas.api.event.DashboardEventDto;
import de.symeda.sormas.api.event.EventCriteria;
import de.symeda.sormas.api.event.EventReferenceDto;
import de.symeda.sormas.api.event.EventStatus;
import de.symeda.sormas.api.task.TaskCriteria;
import de.symeda.sormas.api.user.JurisdictionLevel;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.api.utils.DataHelper;
import de.symeda.sormas.api.utils.DateHelper;
import de.symeda.sormas.backend.action.Action;
import de.symeda.sormas.backend.action.ActionService;
import de.symeda.sormas.backend.caze.Case;
import de.symeda.sormas.backend.caze.CaseService;
import de.symeda.sormas.backend.common.AbstractAdoService;
import de.symeda.sormas.backend.common.AbstractCoreAdoService;
import de.symeda.sormas.backend.common.AbstractDomainObject;
import de.symeda.sormas.backend.common.CoreAdo;
import de.symeda.sormas.backend.location.Location;
import de.symeda.sormas.backend.person.Person;
import de.symeda.sormas.backend.region.District;
import de.symeda.sormas.backend.region.DistrictFacadeEjb.DistrictFacadeEjbLocal;
import de.symeda.sormas.backend.region.Region;
import de.symeda.sormas.backend.task.Task;
import de.symeda.sormas.backend.task.TaskService;
import de.symeda.sormas.backend.user.User;

@Stateless
@LocalBean
public class EventService extends AbstractCoreAdoService<Event> {

	@EJB
	private DistrictFacadeEjbLocal districtFacade;
	@EJB
	private EventParticipantService eventParticipantService;
	@EJB
	private TaskService taskService;
	@EJB
	private ActionService actionService;
	@EJB
	private CaseService caseService;

	public EventService() {
		super(Event.class);
	}

	public List<Event> getAllActiveEventsAfter(Date date) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Event> cq = cb.createQuery(getElementClass());
		Root<Event> from = cq.from(getElementClass());
		from.fetch(Event.EVENT_LOCATION);

		Predicate filter = createActiveEventsFilter(cb, from);

		User user = getCurrentUser();
		if (user != null) {
			EventUserFilterCriteria eventUserFilterCriteria = new EventUserFilterCriteria();
			eventUserFilterCriteria.includeUserCaseFilter(true);
			eventUserFilterCriteria.forceRegionJurisdiction(true);

			Predicate userFilter = createUserFilter(cb, cq, from, eventUserFilterCriteria);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		if (date != null) {
			Predicate dateFilter = createChangeDateFilter(cb, from, DateHelper.toTimestampUpper(date));
			filter = cb.and(filter, dateFilter);
		}

		cq.where(filter);
		cq.orderBy(cb.desc(from.get(Event.CHANGE_DATE)));
		cq.distinct(true);

		return em.createQuery(cq).getResultList();
	}

	public List<String> getAllActiveUuids() {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Event> from = cq.from(getElementClass());

		Predicate filter = createActiveEventsFilter(cb, from);

		User user = getCurrentUser();
		if (user != null) {
			EventUserFilterCriteria eventUserFilterCriteria = new EventUserFilterCriteria();
			eventUserFilterCriteria.includeUserCaseFilter(true);
			eventUserFilterCriteria.forceRegionJurisdiction(true);

			Predicate userFilter = createUserFilter(cb, cq, from, eventUserFilterCriteria);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		cq.where(filter);
		cq.select(from.get(Event.UUID));

		return em.createQuery(cq).getResultList();
	}

	public List<DashboardEventDto> getNewEventsForDashboard(EventCriteria eventCriteria) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<DashboardEventDto> cq = cb.createQuery(DashboardEventDto.class);
		Root<Event> event = cq.from(getElementClass());
		Join<Event, Location> eventLocation = event.join(Event.EVENT_LOCATION, JoinType.LEFT);
		Join<Location, District> eventDistrict = eventLocation.join(Location.DISTRICT, JoinType.LEFT);

		Predicate filter = createDefaultFilter(cb, event);
		filter = and(cb, filter, buildCriteriaFilter(eventCriteria, cb, event));
		filter = and(cb, filter, createUserFilter(cb, cq, event));

		List<DashboardEventDto> result;

		if (filter != null) {
			cq.where(filter);
			cq.multiselect(
				event.get(Event.UUID),
				event.get(Event.EVENT_STATUS),
				event.get(Event.DISEASE),
				event.get(Event.DISEASE_DETAILS),
				event.get(Event.START_DATE),
				event.get(Event.REPORT_LAT),
				event.get(Event.REPORT_LON),
				eventLocation.get(Location.LATITUDE),
				eventLocation.get(Location.LONGITUDE),
				eventDistrict.get(District.UUID));

			result = em.createQuery(cq).getResultList();
			for (DashboardEventDto dashboardEventDto : result) {
				if (dashboardEventDto.getDistrictUuid() != null) {
					dashboardEventDto.setDistrict(districtFacade.getDistrictReferenceByUuid(dashboardEventDto.getDistrictUuid()));
				}
			}
		} else {
			result = Collections.emptyList();
		}

		return result;
	}

	public Map<Disease, Long> getEventCountByDisease(EventCriteria eventCriteria) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Event> event = cq.from(Event.class);

		cq.multiselect(event.get(Event.DISEASE), cb.count(event));
		cq.groupBy(event.get(Event.DISEASE));

		Predicate filter = createDefaultFilter(cb, event);
		filter = and(cb, filter, buildCriteriaFilter(eventCriteria, cb, event));
		filter = and(cb, filter, createUserFilter(cb, cq, event));

		if (filter != null)
			cq.where(filter);

		List<Object[]> results = em.createQuery(cq).getResultList();

		return results.stream().collect(Collectors.toMap(e -> (Disease) e[0], e -> (Long) e[1]));
	}

	public Map<EventStatus, Long> getEventCountByStatus(EventCriteria eventCriteria) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Event> event = cq.from(Event.class);

		cq.multiselect(event.get(Event.EVENT_STATUS), cb.count(event));
		cq.groupBy(event.get(Event.EVENT_STATUS));

		Predicate filter = createDefaultFilter(cb, event);
		filter = and(cb, filter, buildCriteriaFilter(eventCriteria, cb, event));
		filter = and(cb, filter, createUserFilter(cb, cq, event));

		if (filter != null)
			cq.where(filter);

		List<Object[]> results = em.createQuery(cq).getResultList();

		return results.stream().collect(Collectors.toMap(e -> (EventStatus) e[0], e -> (Long) e[1]));
	}

	public List<String> getArchivedUuidsSince(Date since) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Event> event = cq.from(Event.class);

		EventUserFilterCriteria eventUserFilterCriteria = new EventUserFilterCriteria();
		eventUserFilterCriteria.includeUserCaseFilter(true);
		eventUserFilterCriteria.forceRegionJurisdiction(true);

		Predicate filter = createUserFilter(cb, cq, event, eventUserFilterCriteria);
		if (since != null) {
			Predicate dateFilter = cb.greaterThanOrEqualTo(event.get(Event.CHANGE_DATE), since);
			if (filter != null) {
				filter = cb.and(filter, dateFilter);
			} else {
				filter = dateFilter;
			}
		}

		Predicate archivedFilter = cb.equal(event.get(Event.ARCHIVED), true);
		if (filter != null) {
			filter = cb.and(filter, archivedFilter);
		} else {
			filter = archivedFilter;
		}

		cq.where(filter);
		cq.select(event.get(Event.UUID));

		return em.createQuery(cq).getResultList();
	}

	public List<String> getDeletedUuidsSince(Date since) {

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Event> event = cq.from(Event.class);

		EventUserFilterCriteria eventUserFilterCriteria = new EventUserFilterCriteria();
		eventUserFilterCriteria.includeUserCaseFilter(true);
		eventUserFilterCriteria.forceRegionJurisdiction(true);

		Predicate filter = createUserFilter(cb, cq, event, eventUserFilterCriteria);
		if (since != null) {
			Predicate dateFilter = cb.greaterThanOrEqualTo(event.get(Event.CHANGE_DATE), since);
			if (filter != null) {
				filter = cb.and(filter, dateFilter);
			} else {
				filter = dateFilter;
			}
		}

		Predicate deletedFilter = cb.equal(event.get(Event.DELETED), true);
		if (filter != null) {
			filter = cb.and(filter, deletedFilter);
		} else {
			filter = deletedFilter;
		}

		cq.where(filter);
		cq.select(event.get(Event.UUID));

		return em.createQuery(cq).getResultList();
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Predicate createUserFilter(CriteriaBuilder cb, CriteriaQuery cq, From<Event, Event> eventPath) {
		return createUserFilter(cb, cq, eventPath, null);
	}

	@SuppressWarnings("rawtypes")
	public Predicate createUserFilter(
		CriteriaBuilder cb,
		CriteriaQuery cq,
		From<Event, Event> eventPath,
		EventUserFilterCriteria eventUserFilterCriteria) {

		final User currentUser = getCurrentUser();
		final JurisdictionLevel jurisdictionLevel = currentUser.getJurisdictionLevel();
		if (jurisdictionLevel == JurisdictionLevel.NATION || currentUser.hasAnyUserRole(UserRole.REST_USER)) {
			return null;
		}

		Predicate filter = null;

		switch (jurisdictionLevel) {
		case REGION:
			if (currentUser.getRegion() != null) {
				filter = or(cb, filter, cb.equal(eventPath.join(Event.EVENT_LOCATION, JoinType.LEFT).get(Location.REGION), currentUser.getRegion()));
			}
			break;
		case DISTRICT:
			if (currentUser.getDistrict() != null) {
				filter =
					or(cb, filter, cb.equal(eventPath.join(Event.EVENT_LOCATION, JoinType.LEFT).get(Location.DISTRICT), currentUser.getDistrict()));
			}
			break;
		default:
		}

		if (filter != null && currentUser.getLimitedDisease() != null) {
			filter = cb
				.and(filter, cb.or(cb.equal(eventPath.get(Event.DISEASE), currentUser.getLimitedDisease()), cb.isNull(eventPath.get(Event.DISEASE))));
		}

		Predicate filterResponsible = cb.equal(eventPath.join(Event.REPORTING_USER, JoinType.LEFT), currentUser);
		filterResponsible = cb.or(filterResponsible, cb.equal(eventPath.join(Event.SURVEILLANCE_OFFICER, JoinType.LEFT), currentUser));

		if (eventUserFilterCriteria != null && eventUserFilterCriteria.isIncludeUserCaseFilter()) {
			filter = cb.or(filter, createCaseFilter(cb, cq, eventPath));
		}

		if (eventUserFilterCriteria != null && eventUserFilterCriteria.isForceRegionJurisdiction()) {
			filter = cb.or(filter, cb.equal(eventPath.join(Event.EVENT_LOCATION, JoinType.LEFT).get(Location.REGION), currentUser.getRegion()));
		}

		if (filter != null) {
			filter = cb.or(filter, filterResponsible);
		} else {
			filter = filterResponsible;
		}

		return filter;
	}

	public Predicate createCaseFilter(CriteriaBuilder cb, CriteriaQuery cq, From<Event, Event> eventPath) {

		Predicate filter = null;

		Join<Event, EventParticipant> eventParticipantJoin = eventPath.join(Event.EVENT_PERSONS, JoinType.LEFT);
		Join<EventParticipant, Case> caseJoin = eventParticipantJoin.join(EventParticipant.RESULTING_CASE, JoinType.LEFT);

		Subquery<Long> caseSubquery = cq.subquery(Long.class);
		Root<Case> caseRoot = caseSubquery.from(Case.class);
		caseSubquery.where(caseService.createUserFilter(cb, cq, caseRoot));
		caseSubquery.select(caseRoot.get(Case.ID));

		filter = cb.in(caseJoin.get(Case.ID)).value(caseSubquery);

		return filter;

	}

	@Override
	public Predicate createChangeDateFilter(CriteriaBuilder cb, From<?, Event> eventPath, Timestamp date) {

		Predicate dateFilter = greaterThanAndNotNull(cb, eventPath.get(AbstractDomainObject.CHANGE_DATE), date);

		Join<Event, Location> address = eventPath.join(Event.EVENT_LOCATION);
		dateFilter = cb.or(dateFilter, greaterThanAndNotNull(cb, address.get(AbstractDomainObject.CHANGE_DATE), date));

		return dateFilter;
	}

	@Override
	public void delete(Event event) {

		// Delete all event participants associated with this event
		List<EventParticipant> eventParticipants = eventParticipantService.getAllByEventAfter(null, event);
		for (EventParticipant eventParticipant : eventParticipants) {
			eventParticipantService.delete(eventParticipant);
		}

		// Delete all tasks associated with this event
		List<Task> tasks = taskService.findBy(new TaskCriteria().event(new EventReferenceDto(event.getUuid())));
		for (Task task : tasks) {
			taskService.delete(task);
		}

		// Delete all event actions associated with this event
		List<Action> actions = actionService.getAllByEvent(event);
		for (Action action : actions) {
			actionService.delete(action);
		}

		// Mark the event as deleted
		super.delete(event);
	}

	public Predicate buildCriteriaFilter(EventCriteria eventCriteria, CriteriaBuilder cb, Root<Event> from) {

		Predicate filter = null;
		if (eventCriteria.getReportingUserRole() != null) {
			filter = and(
				cb,
				filter,
				cb.isMember(eventCriteria.getReportingUserRole(), from.join(Event.REPORTING_USER, JoinType.LEFT).get(User.USER_ROLES)));
		}
		if (eventCriteria.getDisease() != null) {
			filter = and(cb, filter, cb.equal(from.get(Event.DISEASE), eventCriteria.getDisease()));
		}
		if (eventCriteria.getEventStatus() != null) {
			filter = and(cb, filter, cb.equal(from.get(Event.EVENT_STATUS), eventCriteria.getEventStatus()));
		}
		if (eventCriteria.getTypeOfPlace() != null) {
			filter = and(cb, filter, cb.equal(from.get(Event.TYPE_OF_PLACE), eventCriteria.getTypeOfPlace()));
		}
		if (eventCriteria.getRelevanceStatus() != null) {
			if (eventCriteria.getRelevanceStatus() == EntityRelevanceStatus.ACTIVE) {
				filter = and(cb, filter, cb.or(cb.equal(from.get(Event.ARCHIVED), false), cb.isNull(from.get(Event.ARCHIVED))));
			} else if (eventCriteria.getRelevanceStatus() == EntityRelevanceStatus.ARCHIVED) {
				filter = and(cb, filter, cb.equal(from.get(Event.ARCHIVED), true));
			}
		}
		if (eventCriteria.getDeleted() != null) {
			filter = and(cb, filter, cb.equal(from.get(Event.DELETED), eventCriteria.getDeleted()));
		}
		if (eventCriteria.getRegion() != null) {
			filter = and(
				cb,
				filter,
				cb.equal(
					from.join(Event.EVENT_LOCATION, JoinType.LEFT).join(Location.REGION, JoinType.LEFT).get(Region.UUID),
					eventCriteria.getRegion().getUuid()));
		}
		if (eventCriteria.getDistrict() != null) {
			filter = and(
				cb,
				filter,
				cb.equal(
					from.join(Event.EVENT_LOCATION, JoinType.LEFT).join(Location.DISTRICT, JoinType.LEFT).get(District.UUID),
					eventCriteria.getDistrict().getUuid()));
		}
		if (eventCriteria.getReportedDateFrom() != null || eventCriteria.getReportedDateTo() != null) {
			filter =
				and(cb, filter, cb.between(from.get(Event.REPORT_DATE_TIME), eventCriteria.getReportedDateFrom(), eventCriteria.getReportedDateTo()));
		}
		if (eventCriteria.getEventDateFrom() != null && eventCriteria.getEventDateTo() != null) {
			filter = and(
				cb,
				filter,
				cb.or(
					cb.between(from.get(Event.START_DATE), eventCriteria.getEventDateFrom(), eventCriteria.getEventDateTo()),
					cb.and(
						cb.isNotNull(from.get(Event.END_DATE)),
						cb.lessThan(from.get(Event.START_DATE), eventCriteria.getEventDateFrom()),
						cb.greaterThanOrEqualTo(from.get(Event.END_DATE), eventCriteria.getEventDateFrom()))));
		} else if (eventCriteria.getEventDateFrom() != null) {
			filter = and(
				cb,
				filter,
				cb.or(cb.greaterThanOrEqualTo(from.get(Event.START_DATE), eventCriteria.getEventDateFrom())),
				cb.and(
					cb.isNotNull(from.get(Event.END_DATE)),
					cb.lessThan(from.get(Event.START_DATE), eventCriteria.getEventDateFrom()),
					cb.greaterThanOrEqualTo(from.get(Event.END_DATE), eventCriteria.getEventDateFrom())));
		} else if (eventCriteria.getEventDateTo() != null) {
			filter = and(
				cb,
				filter,
				cb.or(cb.and(cb.isNull(from.get(Event.END_DATE)), cb.lessThanOrEqualTo(from.get(Event.START_DATE), eventCriteria.getEventDateTo()))),
				cb.lessThanOrEqualTo(from.get(Event.END_DATE), eventCriteria.getEventDateTo()));
		}
		if (eventCriteria.getSurveillanceOfficer() != null) {
			filter = and(
				cb,
				filter,
				cb.equal(from.join(Event.SURVEILLANCE_OFFICER, JoinType.LEFT).get(User.UUID), eventCriteria.getSurveillanceOfficer().getUuid()));
		}
		if (eventCriteria.getFreeText() != null) {
			String[] textFilters = eventCriteria.getFreeText().split("\\s+");
			for (int i = 0; i < textFilters.length; i++) {
				String textFilter = "%" + textFilters[i].toLowerCase() + "%";
				if (!DataHelper.isNullOrEmpty(textFilter)) {
					Predicate likeFilters =
						cb.or(cb.like(cb.lower(from.get(Event.UUID)), textFilter), cb.like(cb.lower(from.get(Event.EVENT_DESC)), textFilter));
					filter = and(cb, filter, likeFilters);
				}
			}
		}
		if (eventCriteria.getSrcType() != null) {
			filter = and(cb, filter, cb.equal(from.get(Event.SRC_TYPE), eventCriteria.getSrcType()));
		}

		if (eventCriteria.getCaze() != null) {
			Join<Event, EventParticipant> eventParticipantJoin = from.join(Event.EVENT_PERSONS, JoinType.LEFT);
			Join<EventParticipant, Case> caseJoin = eventParticipantJoin.join(EventParticipant.RESULTING_CASE, JoinType.LEFT);

			filter = and(cb, filter, cb.equal(caseJoin.get(Case.UUID), eventCriteria.getCaze().getUuid()));
		}

		return filter;
	}

	/**
	 * Creates a filter that excludes all events that are either {@link Event#archived} or {@link CoreAdo#deleted}.
	 */
	public Predicate createActiveEventsFilter(CriteriaBuilder cb, Root<Event> root) {
		return cb.and(cb.isFalse(root.get(Event.ARCHIVED)), cb.isFalse(root.get(Event.DELETED)));
	}

	/**
	 * Creates a default filter that should be used as the basis of queries that do not use {@link EventCriteria}.
	 * This essentially removes {@link CoreAdo#deleted} events from the queries.
	 */
	public Predicate createDefaultFilter(CriteriaBuilder cb, Root<Event> root) {
		return cb.isFalse(root.get(Event.DELETED));
	}

	public String getUuidByCaseUuidOrPersonUuid(String searchTerm) {

		if (StringUtils.isEmpty(searchTerm)) {
			return null;
		}

		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Event> root = cq.from(Event.class);
		Join<Event, EventParticipant> eventParticipant = root.join(Event.EVENT_PERSONS, JoinType.LEFT);
		Join<EventParticipant, Person> eventParticipantPerson = eventParticipant.join(EventParticipant.PERSON, JoinType.LEFT);
		Join<EventParticipant, Case> eventParticipantCase = eventParticipant.join(EventParticipant.RESULTING_CASE, JoinType.LEFT);

		Predicate filter = cb.or(
			cb.equal(cb.lower(eventParticipantCase.get(Case.UUID)), searchTerm.toLowerCase()),
			cb.equal(cb.lower(eventParticipantPerson.get(Person.UUID)), searchTerm.toLowerCase()));

		cq.where(filter);
		cq.orderBy(cb.desc(root.get(Event.REPORT_DATE_TIME)));
		cq.select(root.get(Event.UUID));

		try {
			return em.createQuery(cq).setMaxResults(1).getSingleResult();
		} catch (NoResultException e) {
			return null;
		}
	}
}
