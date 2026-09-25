import FullCalendar from '@fullcalendar/react';
import dayGridPlugin from '@fullcalendar/daygrid';
import interactionPlugin from '@fullcalendar/interaction';
import { Assessment, Workflow, HolidayEntry, ScheduleBlock } from '../types';
import { colorFor, isCompleted, statusLabel } from '../utils/workflowLookup';
import './AssessmentCalendar.css';

interface AssessmentCalendarProps {
  assessments: Assessment[];
  onEventClick?: (assessment: Assessment) => void;
  onEventDrop?: (assessmentId: string, newStart: Date, newEnd: Date, revert: () => void) => void;
  onEventResize?: (assessmentId: string, newStart: Date, newEnd: Date, revert: () => void) => void;
  loading?: boolean;
  currentAssessmentId?: string; // ID of the assessment being edited (to highlight it)
  // Flat name->color override, independent of any workflow (e.g. the synthetic RETEST_*
  // statuses ScheduleRetestPage draws, which are not real workflow statuses at all).
  statusColors?: Record<string, string>;
  // The workflow list, for per-row colours keyed off each assessment's own workflowId. Omit
  // it (as CreateAssessment and ScheduleRetestPage do) to keep the old flat-map-only behaviour.
  workflows?: Workflow[];
  initialDate?: string; // ISO date string to navigate to on mount
  /** Called with the visible [start, end] dates (inclusive, YYYY-MM-DD) whenever the view moves. */
  onRangeChange?: (start: string, end: string) => void;
  // Org-wide calendar: the default holiday region's holidays and manager-defined scheduling
  // blocks, drawn as day-tinted background events (not per-user personal time off — that stays
  // on the By User timeline only).
  orgHolidays?: HolidayEntry[];
  blocks?: ScheduleBlock[];
}

/**
 * A row's colour: the owning workflow's colour for its status when `workflows` is supplied,
 * falling back to the flat `statusColors` override, falling back to a neutral grey. Keeping
 * the flat-map fallback lets ScheduleRetestPage's synthetic RETEST_* statuses (which belong
 * to no workflow) and CreateAssessment's unconfigured calendar keep their existing look.
 */
const getStatusColor = (
  workflows: Workflow[] | undefined,
  statusColors: Record<string, string> | undefined,
  workflowId: string | null | undefined,
  status: string
): string => {
  // Completed always renders green, regardless of what a workflow author configured for its
  // terminal status: the point of a calendar is to see who's still booked vs. done at a glance,
  // and a workflow that colours its completed status, say, blue would otherwise hide that signal.
  if (workflows && isCompleted(workflows, workflowId, status)) return '#10b981';
  const workflowColor = workflows && workflows.length > 0 ? colorFor(workflows, workflowId, status) : undefined;
  return workflowColor ?? statusColors?.[status] ?? '#6c757d';
};

/** The assessment being created or edited, picked out from the rest. */
const CURRENT_COLOR = '#8b5cf6';

/** The calendar date part of an API value, dropping the always-midnight time. */
const dateOnly = (value: string): string => value.split('T')[0];

const shiftDays = (isoDate: string, days: number): string => {
  const [y, m, d] = isoDate.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  dt.setDate(dt.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
};

/** Read a Date the calendar hands back as its local calendar date, never via UTC. */
const localDate = (dt: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
};

export default function AssessmentCalendar({
  assessments,
  onEventClick,
  onEventDrop,
  onEventResize,
  loading = false,
  currentAssessmentId,
  statusColors,
  workflows,
  initialDate,
  onRangeChange,
  orgHolidays,
  blocks,
}: AssessmentCalendarProps) {
  const events = assessments
    .filter((a) => a.startDate && a.plannedEndDate)
    .map((assessment) => {
      const isCurrentAssessment = currentAssessmentId && assessment.id === currentAssessmentId;
      const baseColor = getStatusColor(workflows, statusColors, assessment.workflowId, assessment.status);
      const isEditable = currentAssessmentId ? isCurrentAssessment : true;

      return {
        id: assessment.id,
        title:
          assessment.assessorNames && assessment.assessorNames.length > 0
            ? `${assessment.name} — ${assessment.assessorNames.join(', ')}`
            : assessment.name,
        // Date-only, all-day events: the API's start/plannedEndDate are LocalDateTime at
        // midnight, and feeding those through as timed events shifts them by the viewer's
        // UTC offset. FullCalendar's `end` is exclusive, so the planned end is pushed one day
        // out — otherwise the bar stops the day before the assessment actually ends.
        allDay: true,
        start: dateOnly(assessment.startDate!),
        end: shiftDays(dateOnly(assessment.plannedEndDate!), 1),
        // Bars are painted in CSS from --bar-color (set in eventDidMount) — a tinted fill with a
        // status-coloured edge, matching the Engagements By User timeline — not FullCalendar's
        // solid inline colours.
        backgroundColor: 'transparent',
        borderColor: 'transparent',
        classNames: [
          'tl-event',
          ...(assessment.isPastDue ? ['past-due'] : []),
          ...(isCurrentAssessment ? ['current'] : []),
        ],
        editable: isEditable ? true : false, // Controls both drag and resize
        extendedProps: {
          assessment,
          isCurrentAssessment,
          color: isCurrentAssessment ? CURRENT_COLOR : baseColor,
        },
      };
    });

  // Org-wide holidays and scheduling blocks: background events so they tint the day cells
  // without competing with assessment bars for event rows. FullCalendar gives these no native
  // tooltip, so eventDidMount below sets a title attribute instead.
  const orgHolidayEvents = (orgHolidays ?? []).map((holiday) => ({
    start: holiday.date,
    end: shiftDays(holiday.date, 1),
    allDay: true,
    display: 'background' as const,
    classNames: ['cal-org-event', 'cal-org-event--holiday'],
    title: holiday.name,
  }));

  const blockEvents = (blocks ?? []).map((block) => ({
    start: block.startDate,
    end: shiftDays(block.endDate, 1),
    allDay: true,
    display: 'background' as const,
    classNames: ['cal-org-event', 'cal-org-event--block'],
    title: block.note ? `${block.title} — ${block.note}` : block.title,
  }));

  const allEvents = [...events, ...orgHolidayEvents, ...blockEvents];

  const handleEventClick = (info: any) => {
    // Holiday/block background events carry no `assessment` in their extendedProps — FullCalendar
    // fires eventClick for them same as any other event, and forwarding undefined on to the
    // caller's edit navigation throws.
    if (info.event.display === 'background') return;
    if (onEventClick) {
      const assessment = info.event.extendedProps.assessment;
      onEventClick(assessment);
    }
  };

  /**
   * The dates a drag or resize produced, converted back to the inclusive range callers store:
   * the event's `end` is exclusive, so the last day of the assessment is the day before it.
   */
  const draggedRange = (info: any): { start: Date; end: Date } => {
    const start = info.event.start as Date;
    const rawEnd = (info.event.end as Date | null) ?? start;
    const [y, m, d] = shiftDays(localDate(rawEnd), -1).split('-').map(Number);
    const end = new Date(y, m - 1, d);
    return { start, end: end < start ? start : end };
  };

  const handleEventDrop = (info: any) => {
    // Background events are never editable (see `editable` below), but guard defensively anyway —
    // a background event has no assessment id to update.
    if (info.event.display === 'background') return;
    if (onEventDrop) {
      const { start, end } = draggedRange(info);
      onEventDrop(info.event.id, start, end, () => info.revert());
    }
  };

  const handleEventResize = (info: any) => {
    if (info.event.display === 'background') return;
    if (onEventResize) {
      const { start, end } = draggedRange(info);
      onEventResize(info.event.id, start, end, () => info.revert());
    }
  };

  if (loading) {
    return (
      <div className="text-center py-5">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="assessment-calendar">
      <FullCalendar
        plugins={[dayGridPlugin, interactionPlugin]}
        initialView="dayGridMonth"
        initialDate={initialDate}
        eventDidMount={(info) => {
          if (info.event.display === 'background') {
            info.el.setAttribute('title', info.event.title);
            return;
          }
          info.el.style.setProperty('--bar-color', info.event.extendedProps.color);
        }}
        buttonText={{ today: 'Today', month: 'Month', week: 'Week', day: 'Day' }}
        headerToolbar={{
          // The arrows sit in their own fixed-width chunk (see AssessmentCalendar.css), so they never
          // shift as the centered title's length changes.
          left: 'prev,next today',
          center: 'title',
          // Assessments are all-day, so an hour-by-hour time grid is empty space: day grids instead.
          right: 'dayGridDay,dayGridWeek,dayGridMonth',
        }}
        events={allEvents}
        eventClick={handleEventClick}
        eventDrop={handleEventDrop}
        eventResize={handleEventResize}
        // FullCalendar's range end is exclusive; hand back the last visible day instead.
        datesSet={(info) => onRangeChange?.(localDate(info.start), shiftDays(localDate(info.end), -1))}
        editable={!!(onEventDrop || onEventResize)}
        eventDurationEditable={true}
        eventStartEditable={true}
        droppable={!!(onEventDrop || onEventResize)}
        eventResizableFromStart={true}
        height="auto"
        aspectRatio={1.8}
        eventDisplay="block"
        // Assessments are scheduled by date, not time of day, so there is no clock to show.
        displayEventTime={false}
      />

      {/* Legend — driven by statuses present in the current event set */}
      <div className="calendar-legend">
        {currentAssessmentId && (
          <span className="badge current" style={{ ['--bar-color' as string]: CURRENT_COLOR }}>
            Current (Editing)
          </span>
        )}
        {(() => {
          // Keyed on the (status, colour) pair, not the name alone — two workflows' same-named
          // statuses only collapse into one legend entry when they also agree on colour.
          const entries = new Map<string, { status: string; workflowId?: string; color: string }>();
          for (const a of assessments) {
            const color = getStatusColor(workflows, statusColors, a.workflowId, a.status);
            const key = `${a.status}::${color}`;
            if (!entries.has(key)) entries.set(key, { status: a.status, workflowId: a.workflowId, color });
          }
          return Array.from(entries.values()).map((entry) => (
            <span
              key={`${entry.status}::${entry.color}`}
              className="badge"
              style={{ ['--bar-color' as string]: entry.color }}
            >
              {statusLabel(workflows ?? [], entry.workflowId, entry.status)}
            </span>
          ));
        })()}
        <span className="badge past-due" style={{ ['--bar-color' as string]: '#6c757d' }}>
          Past Due (Red Border)
        </span>
        {orgHolidayEvents.length > 0 && (
          <span className="cal-legend-entry">
            <i className="cal-legend-swatch cal-legend-swatch--holiday" /> Holiday
          </span>
        )}
        {blockEvents.length > 0 && (
          <span className="cal-legend-entry">
            <i className="cal-legend-swatch cal-legend-swatch--block" /> Block
          </span>
        )}
      </div>
    </div>
  );
}
