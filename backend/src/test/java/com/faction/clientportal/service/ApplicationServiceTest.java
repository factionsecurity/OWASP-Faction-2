package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationCommentDto;
import com.faction.clientportal.dto.AddCommentRequest;
import com.faction.clientportal.dto.ApplicationDto;
import com.faction.clientportal.dto.CreateApplicationRequest;
import com.faction.clientportal.dto.UpdateApplicationRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationComment;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private EntityFieldConfigRepository entityFieldConfigRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationIdConfigService applicationIdConfigService;

    @Mock
    private MentionQueueService mentionQueueService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private com.faction.clientportal.service.email.ThreadCommentEmailSender threadCommentEmailSender;

    @Mock
    private com.faction.clientportal.service.AccessScopeService accessScopeService;

    @InjectMocks
    private ApplicationService applicationService;

    @BeforeEach
    void setUp() {
        lenient().when(entityFieldConfigRepository.findByScope(any())).thenReturn(Optional.empty());
        lenient().when(applicationRepository.save(any(Application.class))).thenAnswer(inv -> {
            Application app = inv.getArgument(0);
            if (app.getId() == null) {
                app.setId("generated-id");
            }
            return app;
        });
    }

    @Test
    void createApplication_autoGeneratesAppIdWhenEnabled() {
        when(applicationIdConfigService.isEnabled()).thenReturn(true);
        when(applicationIdConfigService.generateNextAppId()).thenReturn("ASMT-1");

        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .name("My App")
                .build();

        ApplicationDto dto = applicationService.createApplication(request, "user-1");

        assertThat(dto.getAppId()).isEqualTo("ASMT-1");
        verify(applicationIdConfigService).generateNextAppId();
    }

    @Test
    void createApplication_skipsGenerationWhenDisabled() {
        when(applicationIdConfigService.isEnabled()).thenReturn(false);

        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .name("My App")
                .build();

        ApplicationDto dto = applicationService.createApplication(request, "user-1");

        assertThat(dto.getAppId()).isNull();
        verify(applicationIdConfigService, never()).generateNextAppId();
    }

    @Test
    void createApplication_usesProvidedAppId() {
        when(applicationRepository.findByAppId("CUSTOM-7")).thenReturn(Optional.empty());

        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .name("My App")
                .appId("CUSTOM-7")
                .build();

        ApplicationDto dto = applicationService.createApplication(request, "user-1");

        assertThat(dto.getAppId()).isEqualTo("CUSTOM-7");
        verify(applicationIdConfigService, never()).generateNextAppId();
    }

    @Test
    void createApplication_rejectsDuplicateAppId() {
        when(applicationRepository.findByAppId("ASMT-1"))
                .thenReturn(Optional.of(Application.builder().id("other").appId("ASMT-1").build()));

        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .name("My App")
                .appId("ASMT-1")
                .build();

        assertThatThrownBy(() -> applicationService.createApplication(request, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId 'ASMT-1' already exists");
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void createApplication_allowsDuplicateNames() {
        // Name uniqueness was removed — two apps may share a name (only appId is unique)
        when(applicationIdConfigService.isEnabled()).thenReturn(true);
        when(applicationIdConfigService.generateNextAppId()).thenReturn("ASMT-2");

        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .name("Duplicate Name")
                .build();

        ApplicationDto dto = applicationService.createApplication(request, "user-1");

        assertThat(dto.getName()).isEqualTo("Duplicate Name");
        verify(applicationRepository, never()).existsByName(any());
    }

    @Test
    void updateApplication_setsNewAppId() {
        Application existing = Application.builder()
                .id("app-1")
                .appId("ASMT-1")
                .name("My App")
                .createdAt(LocalDateTime.now())
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));
        when(applicationRepository.findByAppId("ASMT-99")).thenReturn(Optional.empty());

        UpdateApplicationRequest request = UpdateApplicationRequest.builder()
                .name("My App")
                .appId("ASMT-99")
                .build();

        ApplicationDto dto = applicationService.updateApplication("app-1", request, "user-1");

        assertThat(dto.getAppId()).isEqualTo("ASMT-99");
    }

    @Test
    void updateApplication_rejectsConflictingAppId() {
        Application existing = Application.builder()
                .id("app-1")
                .appId("ASMT-1")
                .name("My App")
                .createdAt(LocalDateTime.now())
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));
        when(applicationRepository.findByAppId("ASMT-2"))
                .thenReturn(Optional.of(Application.builder().id("app-2").appId("ASMT-2").build()));

        UpdateApplicationRequest request = UpdateApplicationRequest.builder()
                .name("My App")
                .appId("ASMT-2")
                .build();

        assertThatThrownBy(() -> applicationService.updateApplication("app-1", request, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId 'ASMT-2' already exists");
    }

    @Test
    void updateApplication_keepsAppIdWhenNotProvided() {
        Application existing = Application.builder()
                .id("app-1")
                .appId("ASMT-1")
                .name("My App")
                .createdAt(LocalDateTime.now())
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));

        UpdateApplicationRequest request = UpdateApplicationRequest.builder()
                .name("Renamed App")
                .build();

        ApplicationDto dto = applicationService.updateApplication("app-1", request, "user-1");

        assertThat(dto.getAppId()).isEqualTo("ASMT-1");
        assertThat(dto.getName()).isEqualTo("Renamed App");
        ArgumentCaptor<Application> captor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(captor.capture());
        assertThat(captor.getValue().getAppId()).isEqualTo("ASMT-1");
    }

    @Test
    void addComment_appendsCommentAndQueuesMentions() {
        Application existing = Application.builder()
                .id("app-1")
                .name("My App")
                .comments(new ArrayList<>())
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));
        when(userRepository.findByUsername("user-1")).thenReturn(Optional.of(
                User.builder().username("user-1").firstName("Jane").lastName("Doe").build()));

        AddCommentRequest request = new AddCommentRequest();
        request.setContent("Hello @app-owner");

        List<ApplicationCommentDto> result = applicationService.addComment("app-1", request, "user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Hello @app-owner");
        assertThat(result.get(0).getAuthorId()).isEqualTo("user-1");
        assertThat(result.get(0).getAuthorName()).isEqualTo("Jane Doe");
        verify(mentionQueueService).queueMentions(eq("Hello @app-owner"), any(), eq("user-1"), any());
    }

    @Test
    void addComment_throwsWhenApplicationNotFound() {
        when(applicationRepository.findById("missing")).thenReturn(Optional.empty());

        AddCommentRequest request = new AddCommentRequest();
        request.setContent("Hello");

        assertThatThrownBy(() -> applicationService.addComment("missing", request, "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteComment_removesOwnComment() {
        ApplicationComment comment = ApplicationComment.builder()
                .id("comment-1")
                .authorId("user-1")
                .content("To be deleted")
                .build();
        Application existing = Application.builder()
                .id("app-1")
                .name("My App")
                .comments(new ArrayList<>(List.of(comment)))
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));

        List<ApplicationCommentDto> result = applicationService.deleteComment("app-1", "comment-1", "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteComment_rejectsDeletingAnotherUsersComment() {
        ApplicationComment comment = ApplicationComment.builder()
                .id("comment-1")
                .authorId("user-1")
                .content("Not yours")
                .build();
        Application existing = Application.builder()
                .id("app-1")
                .name("My App")
                .comments(new ArrayList<>(List.of(comment)))
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> applicationService.deleteComment("app-1", "comment-1", "user-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only delete your own comments");
    }

    @Test
    void deleteComment_rejectsDeletingSystemGeneratedComment() {
        ApplicationComment comment = ApplicationComment.builder()
                .id("comment-1")
                .authorId("user-1")
                .content("System note")
                .systemGenerated(true)
                .build();
        Application existing = Application.builder()
                .id("app-1")
                .name("My App")
                .comments(new ArrayList<>(List.of(comment)))
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> applicationService.deleteComment("app-1", "comment-1", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("System-generated comments cannot be deleted");
    }

    @Test
    void addSystemComment_appendsSystemGeneratedCommentAndResolvesActor() {
        Application existing = Application.builder()
                .id("app-1")
                .name("My App")
                .comments(new ArrayList<>())
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(existing));
        when(userRepository.findByUsername("user-1")).thenReturn(Optional.of(
                User.builder().username("user-1").firstName("Jane").lastName("Doe").build()));

        applicationService.addSystemComment("app-1", "**Assessment scheduled** by {actor}.", "user-1");

        assertThat(existing.getComments()).hasSize(1);
        ApplicationComment comment = existing.getComments().get(0);
        assertThat(comment.isSystemGenerated()).isTrue();
        assertThat(comment.getContent()).isEqualTo("**Assessment scheduled** by Jane Doe.");
        assertThat(comment.getAuthorId()).isEqualTo("user-1");
        assertThat(comment.getAuthorName()).isEqualTo("Jane Doe");
        verify(applicationRepository).save(existing);
        verify(mentionQueueService, never()).queueMentions(any(), any(), any(), any());
    }

    @Test
    void addSystemComment_noOpsWhenApplicationMissingOrIdNull() {
        when(applicationRepository.findById("missing")).thenReturn(Optional.empty());

        applicationService.addSystemComment("missing", "message", "user-1");
        applicationService.addSystemComment(null, "message", "user-1");

        verify(applicationRepository, never()).save(any());
    }
}
