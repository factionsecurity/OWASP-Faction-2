import { useEffect, useState, useCallback } from 'react';
import { Edit2, Trash2, Plus, Star } from 'lucide-react';
import { campaignsApi } from '../api';
import type { Campaign } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import {
  Modal,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  FormGroup,
  FormLabel,
  Input,
  ErrorMessage,
  ConfirmDialog,
} from '../components';
import Page from '../components/Page';
import './Teams.css';

interface CampaignsProps {
  /** Render without the Page wrapper, for embedding as a tab in Assessment Config */
  embedded?: boolean;
}

export default function Campaigns({ embedded = false }: CampaignsProps) {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedCampaign, setSelectedCampaign] = useState<Campaign | null>(null);
  const [name, setName] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);

  useEffect(() => {
    loadCampaigns();
  }, [pagination.page, pagination.pageSize, searchQuery, sort]);

  const loadCampaigns = async () => {
    try {
      setLoading(true);
      const response = await campaignsApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort));
      if (response.data) {
        setCampaigns(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load campaigns');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = () => {
    setModalMode('create');
    setSelectedCampaign(null);
    setName('');
    setError('');
    setShowModal(true);
  };

  const handleEdit = (campaign: Campaign) => {
    setModalMode('edit');
    setSelectedCampaign(campaign);
    setName(campaign.name);
    setError('');
    setShowModal(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      if (modalMode === 'create') {
        await campaignsApi.create({ name });
      } else if (selectedCampaign) {
        await campaignsApi.update(selectedCampaign.id, { name });
      }
      setShowModal(false);
      await loadCampaigns();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save campaign');
    }
  };

  const handleSetDefault = async (campaign: Campaign) => {
    try {
      await campaignsApi.update(campaign.id, { isDefault: !campaign.isDefault });
      await loadCampaigns();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to update default campaign');
    }
  };

  const handleConfirmedDelete = async () => {
    if (!confirmDeleteId) return;
    try {
      setDeleting(true);
      setDeleteError('');
      await campaignsApi.delete(confirmDeleteId);
      setConfirmDeleteId(null);
      await loadCampaigns();
    } catch (err: any) {
      setDeleteError(err.response?.data?.message || 'Failed to delete campaign');
    } finally {
      setDeleting(false);
    }
  };

  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({ ...prev, page }));
  }, []);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination((prev) => ({ ...prev, page: 0, pageSize }));
  }, []);

  const handleSearchChange = useCallback((search: string) => {
    setSearchQuery(search);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  // Re-sorting reshuffles the whole result set, so the current page number is
  // meaningless afterwards — go back to the first page.
  const handleSortChange = useCallback((next: SortState | null) => {
    setSort(next);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const columns: Column<Campaign>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
      render: (campaign) => <div className="font-medium">{campaign.name}</div>,
    },
    {
      header: 'Default',
      sortKey: 'isDefault',
      render: (campaign) =>
        campaign.isDefault ? <Badge variant="success">Default</Badge> : null,
    },
    {
      header: 'Created',
      sortKey: 'createdAt',
      render: (campaign) => (
        <span className="text-sm text-muted">
          {new Date(campaign.createdAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      header: 'Actions',
      width: '150px',
      render: (campaign) => (
        <ActionButtons>
          <IconButton
            icon={Edit2}
            variant="edit"
            title="Rename"
            onClick={() => handleEdit(campaign)}
          />
          <IconButton
            icon={Star}
            variant="info"
            title={campaign.isDefault ? 'Unset default' : 'Set as default'}
            onClick={() => handleSetDefault(campaign)}
          />
          <IconButton
            icon={Trash2}
            variant="delete"
            title="Delete"
            onClick={() => {
              setDeleteError('');
              setConfirmDeleteId(campaign.id);
            }}
          />
        </ActionButtons>
      ),
    },
  ];

  const Wrapper = embedded ? 'div' : Page;

  return (
    <Wrapper className="teams-page">
      <div className="page-header">
        {embedded ? <h2>Campaigns</h2> : <div />}
        <Button variant="primary" icon={Plus} onClick={handleCreate}>
          Create Campaign
        </Button>
      </div>

      {error && !showModal && <ErrorMessage>{error}</ErrorMessage>}

      <DataTable
        columns={columns}
        data={campaigns}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search campaigns"
        emptyMessage="No campaigns found"
        idAccessor="id"
        sort={sort}
        onSortChange={handleSortChange}
      />

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={modalMode === 'create' ? 'Create New Campaign' : 'Rename Campaign'}
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              {modalMode === 'create' ? 'Create' : 'Save Changes'}
            </Button>
          </>
        }
      >
        {error && <ErrorMessage>{error}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Campaign Name</FormLabel>
          <Input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            placeholder="Enter campaign name"
          />
        </FormGroup>
      </Modal>

      <ConfirmDialog
        isOpen={!!confirmDeleteId}
        onClose={() => setConfirmDeleteId(null)}
        onConfirm={handleConfirmedDelete}
        title="Delete Campaign"
        message={
          deleteError ||
          'Are you sure you want to delete this campaign? Deletion is blocked while any assessment is assigned to it.'
        }
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />
    </Wrapper>
  );
}
