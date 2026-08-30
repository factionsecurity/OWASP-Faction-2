import FullCalendar from '@fullcalendar/react';
import dayGridPlugin from '@fullcalendar/daygrid';
import timeGridPlugin from '@fullcalendar/timegrid';
import interactionPlugin from '@fullcalendar/interaction';
import { Assessment } from '../types';

interface AssessmentCalendarProps {
  assessments: Assessment[];
  onEventClick?: (assessment: Assessment) => void;
  onEventDrop?: (assessmentId: string, newStart: Date, newEnd: Date, revert: () => void) => void;
  onEventResize?: (assessmentId: string, newStart: Date, newEnd: Date, revert: () => void) => void;
  loading?: boolean;
  currentAssessmentId?: string; // ID of the assessment being edited (to highlight it)
  statusColors?: Record<string, string>;
  initialDate?: string; // ISO date string to navigate to on mount
}

const DEFAULT_STATUS_COLORS: Record<string, string> = {
  DRAFT: '#6c757d',
  IN_PROGRESS: '#0d6efd',
  ON_HOLD: '#ffc107',
  PENDING_REVIEW: '#0dcaf0',
  COMPLETED: '#198754',
  APPROVED: '#20c997',
  ARCHIVED: '#212529',
};

const getStatusColor = (status: string, customColors?: Record<string, string>): string => {
  return customColors?.[status] || DEFAULT_STATUS_COLORS[status] || '#6c757d';
};

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
  initialDate,
}: AssessmentCalendarProps) {
  const events = assessments
    .filter((a) => a.startDate && a.plannedEndDate)
    .map((assessment) => {
      const isCurrentAssessment = currentAssessmentId && assessment.id === currentAssessmentId;
      const baseColor = getStatusColor(assessment.status, statusColors);
      const isEditable = currentAssessmentId ? isCurrentAssessment : true;

      return {
        id: assessment.id,
        title: assessment.name,
        // Date-only, all-day events: the API's start/plannedEndDate are LocalDateTime at
        // midnight, and feeding those through as timed events shifts them by the viewer's
        // UTC offset. FullCalendar's `end` is exclusive, so the planned end is pushed one day
        // out — otherwise the bar stops the day before the assessment actually ends.
        allDay: true,
        start: dateOnly(assessment.startDate!),
        end: shiftDays(dateOnly(assessment.plannedEndDate!), 1),
        backgroundColor: isCurrentAssessment ? '#8b5cf6' : baseColor, // Purple for current
        borderColor: isCurrentAssessment
          ? '#7c3aed'
          : assessment.isPastDue
            ? '#dc3545'
            : baseColor,
        editable: isEditable ? true : false, // Controls both drag and resize
        extendedProps: {
          assessment,
          isCurrentAssessment,
        },
      };
    });

  const handleEventClick = (info: any) => {
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
    if (onEventDrop) {
      const { start, end } = draggedRange(info);
      onEventDrop(info.event.id, start, end, () => info.revert());
    }
  };

  const handleEventResize = (info: any) => {
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
      <style>{`
        .fc {
          font-family: inherit;
        }
        .fc-event {
          cursor: pointer;
          border-width: 2px;
        }
        .fc-event:hover {
          opacity: 0.85;
        }
        .fc-daygrid-event {
          white-space: normal;
        }
        .fc-toolbar-title {
          font-size: 1.5rem;
        }
        .fc-button {
          text-transform: capitalize;
        }
        /* Make resize handles more visible */
        .fc-event-resizer {
          display: block !important;
          position: absolute;
          z-index: 4;
          width: 10px;
          height: 100%;
          top: 0;
        }
        .fc-event-resizer-start {
          left: 0;
          cursor: w-resize;
          background: rgba(255, 255, 255, 0.2);
        }
        .fc-event-resizer-end {
          right: 0;
          cursor: e-resize;
          background: rgba(255, 255, 255, 0.2);
        }
        .fc-event:hover .fc-event-resizer-start,
        .fc-event:hover .fc-event-resizer-end {
          background: rgba(255, 255, 255, 0.6);
        }
        /* Show appropriate cursors */
        .fc-daygrid-event.fc-event-resizable {
          cursor: move;
        }
        .fc-direction-ltr .fc-daygrid-event.fc-event-end.fc-event-resizable-after:hover,
        .fc-direction-ltr .fc-daygrid-block-event.fc-event-end:hover {
          cursor: e-resize;
        }
        .fc-direction-ltr .fc-daygrid-event.fc-event-start.fc-event-resizable-before:hover,
        .fc-direction-ltr .fc-daygrid-block-event.fc-event-start:hover {
          cursor: w-resize;
        }
      `}</style>

      <FullCalendar
        plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
        initialView="dayGridMonth"
        initialDate={initialDate}
        headerToolbar={{
          left: 'prev,next today',
          center: 'title',
          right: 'dayGridMonth,timeGridWeek,timeGridDay',
        }}
        events={events}
        eventClick={handleEventClick}
        eventDrop={handleEventDrop}
        eventResize={handleEventResize}
        editable={!!(onEventDrop || onEventResize)}
        eventDurationEditable={true}
        eventStartEditable={true}
        droppable={!!(onEventDrop || onEventResize)}
        eventResizableFromStart={true}
        height="auto"
        aspectRatio={1.8}
        eventDisplay="block"
        // Assessments are scheduled by date, not time of day — every event is all-day, so
        // there is no clock to show. Without this the week/day views prefix each bar with a
        // meaningless "00:00".
        displayEventTime={false}
        allDayText="Assessments"
      />

      {/* Legend — driven by statuses present in the current event set */}
      <div className="calendar-legend">
        {currentAssessmentId && (
          <span className="badge" style={{ backgroundColor: '#8b5cf6', border: '3px solid #7c3aed' }}>
            Current (Editing)
          </span>
        )}
        {Array.from(new Set(assessments.map((a) => a.status))).map((status) => {
          const color = getStatusColor(status, statusColors);
          const isDark = color === '#212529' || color === '#ffc107';
          return (
            <span
              key={status}
              className="badge"
              style={{ backgroundColor: color, color: isDark ? '#000' : '#fff' }}
            >
              {status}
            </span>
          );
        })}
        <span className="badge" style={{ backgroundColor: '#6c757d', border: '2px solid #dc3545' }}>
          Past Due (Red Border)
        </span>
      </div>
    </div>
  );
}
