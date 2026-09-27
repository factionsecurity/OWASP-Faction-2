import Modal from './Modal';
import ReportDocumentsPanel from './ReportDocumentsPanel';
import RetestReportPanel from './RetestReportPanel';

interface Props {
  assessmentId: string;
  assessmentName: string;
  isOpen: boolean;
  onClose: () => void;
}

/**
 * Everything a tester hands a client for one assessment, without leaving the page they're on:
 * the assessment report's downloads (and PDF password) and the retest report, which can be
 * generated or regenerated here.
 */
export default function AssessmentReportsDialog({ assessmentId, assessmentName, isOpen, onClose }: Props) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} title={`Reports — ${assessmentName}`} size="lg">
      {isOpen && (
        <div className="assessment-reports-dialog">
          <ReportDocumentsPanel assessmentId={assessmentId} downloadOnly />
          <RetestReportPanel assessmentId={assessmentId} />
        </div>
      )}
    </Modal>
  );
}
