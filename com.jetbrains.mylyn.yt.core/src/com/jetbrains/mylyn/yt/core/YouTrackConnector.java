/**
 * @author: amarch
 */

package com.jetbrains.mylyn.yt.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.AbstractRepositoryConnector;
import org.eclipse.mylyn.tasks.core.IRepositoryQuery;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITask.PriorityLevel;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskDataCollector;
import org.eclipse.mylyn.tasks.core.data.TaskMapper;
import org.eclipse.mylyn.tasks.core.sync.ISynchronizationSession;
import org.eclipse.mylyn.tasks.ui.editors.TaskEditor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.jetbrains.youtrack.javarest.client.YouTrackClient;
import com.jetbrains.youtrack.javarest.client.YouTrackClientFactory;
import com.jetbrains.youtrack.javarest.client.YouTrackIssue;
import com.jetbrains.youtrack.javarest.client.YouTrackProject;
import com.jetbrains.youtrack.javarest.utils.MyRunnable;

public class YouTrackConnector extends AbstractRepositoryConnector {

  private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

  private static Map<TaskRepository, YouTrackClient> clientByRepository =
      new HashMap<TaskRepository, YouTrackClient>();

  private static Map<TaskRepository, HashSet<YouTrackProject>> projectsByRepository =
      new HashMap<TaskRepository, HashSet<YouTrackProject>>();

  private final YouTrackTaskDataHandler taskDataHandler;

  private static final int ISSUES_PER_ONE_QUERY = 20;

  public final static String ISSUE_URL_PREFIX = "/issue/";

  private static YouTrackClientFactory clientFactory;

  public YouTrackConnector() {
    taskDataHandler = new YouTrackTaskDataHandler(this);
    clientFactory = new YouTrackClientFactory();
  }

  public static YouTrackProject getProject(TaskRepository repository, String projectname) {
    if (!projectsByRepository.keySet().contains(repository)) {
      YouTrackClient client = getClient(repository);
      List<YouTrackProject> projects = client.getProjects();
      if (projects.size() == 0) {
        boolean loged = client.login(repository.getUserName(), repository.getPassword());
        return loged ? getProject(repository, projectname) : null;
      }
      projectsByRepository.put(repository, new HashSet<YouTrackProject>(projects));
    }

    HashSet<YouTrackProject> projects = projectsByRepository.get(repository);

    for (YouTrackProject project : projects) {
      if (project.getProjectShortName().equals(projectname)) {
        return project;
      }
    }
    return null;
  }

  public static void updateProjectCustomFields(TaskRepository repository, String projectname) {

    final YouTrackProject project = YouTrackConnector.getProject(repository, projectname);

    final YouTrackClient client = clientByRepository.get(repository);

    if (project != null) {
      if (!project.isCustomFieldsUpdated()) {
        project.updateCustomFields(client);
      }
    } else {
      for (YouTrackProject repoProject : projectsByRepository.get(repository)) {
        if (!repoProject.isCustomFieldsUpdated()) {
          repoProject.updateCustomFields(client);
        }
      }
    }
  }

  public static void forceUpdateProjectCustomFields(TaskRepository repository, String projectname) {

    final YouTrackProject project = YouTrackConnector.getProject(repository, projectname);

    final YouTrackClient client = clientByRepository.get(repository);

    if (project != null) {
      project.updateCustomFields(client);
    }
  }

  public static synchronized YouTrackClient getClient(TaskRepository repository) {
    YouTrackClient client = clientByRepository.get(repository);
    if (client == null) {
      client = clientFactory.getClient(repository.getRepositoryUrl());
      clientByRepository.put(repository, client);
    }
    return client;
  }

  @Override
  public boolean canCreateNewTask(TaskRepository repository) {
    return true;
  }

  @Override
  public boolean canCreateTaskFromKey(TaskRepository repository) {
    return true;
  }

  @Override
  public String getConnectorKind() {
    return YouTrackCorePlugin.CONNECTOR_KIND;
  }

  @Override
  public String getLabel() {
    return "YouTrack Repository";
  }

  @Override
  public String getRepositoryUrlFromTaskUrl(String taskFullUrl) {
    // ignore
    return null;
  }

  @Override
  public TaskData getTaskData(TaskRepository taskRepository, String taskId, IProgressMonitor monitor)
      throws CoreException {

    YouTrackIssue issue =
        getClient(taskRepository).getIssue(getRealIssueId(taskId, taskRepository));
    // stall a while to allow the UI to update
    monitor.worked(10);
    return taskDataHandler.readTaskData(taskRepository, issue, monitor);
  }

  @Override
  public String getTaskIdFromTaskUrl(String taskFullUrl) {
    // ignore
    return null;
  }

  @Override
  public String getTaskUrl(String repositoryUrl, String taskId) {
    return repositoryUrl + ISSUE_URL_PREFIX + taskId;
  }

  @Override
  public boolean hasTaskChanged(TaskRepository taskRepository, ITask task, TaskData taskData) {
    // ignore
    return false;
  }

  @Override
  public IStatus performQuery(final TaskRepository repository, final IRepositoryQuery query,
      final TaskDataCollector collector, ISynchronizationSession session,
      final IProgressMonitor monitor) {

    return new MyRunnable() {
      @Override
      public void run() throws Exception {
        List<YouTrackIssue> issues = null;
        String projectname = query.getAttribute(YouTrackCorePlugin.QUERY_KEY_PROJECT);
        String filter = query.getAttribute(YouTrackCorePlugin.QUERY_KEY_FILTER);

        int issuesCount = queryIssuesAmount(projectname, filter, repository);
        issues =
            getClient(repository).getIssuesByFilter(getFilter(projectname, filter, repository),
                issuesCount);

        for (YouTrackIssue issue : issues) {
          TaskData taskData = taskDataHandler.readTaskData(repository, issue, monitor);
          collector.accept(taskData);
        }
      }
    }.execute("Query failed", YouTrackCorePlugin.ID_PLUGIN);

  }

  public String getFilter(String projectname, String filter, TaskRepository repository) {

    String returnFilter = new String(filter);
    if (filter != null) {
      if (projectname != null && !projectname.equals("")) {
        returnFilter += "project: " + projectname;
      }
    } else {
      returnFilter = "project: " + projectname;
    }

    // TODO: fix
    returnFilter += " sort by: {issue id}";

    return returnFilter;

  }

  public int queryIssuesAmount(String projectname, String filter, TaskRepository repository)
      throws CoreException {
    return getClient(repository).getNumberOfIssues(getFilter(projectname, filter, repository));
  }

  @Override
  public void updateRepositoryConfiguration(TaskRepository taskRepository, IProgressMonitor monitor)
      throws CoreException {
    // ignore

  }

  @Override
  public TaskMapper getTaskMapping(TaskData taskData) {
    return new TaskMapper(taskData);
  }

  @Override
  public void updateTaskFromTaskData(TaskRepository repository, ITask task, TaskData taskData) {
    getTaskMapping(taskData).applyTo(task);
  }

  @Override
  public AbstractTaskDataHandler getTaskDataHandler() {
    return taskDataHandler;
  }

  /**
   * @param priority
   * @param priorities - priorities in descending order
   * @return Mylyn PriorityLevel of priority
   */
  public PriorityLevel toPriorityLevel(String priority, LinkedList<String> priorities) {
    int level;
    int count = priorities.size();
    level = (priorities.indexOf(priority) + 1) / ((int) Math.ceil((double) count / 5));

    switch (level) {
      case 1:
        return PriorityLevel.P1;
      case 2:
        return PriorityLevel.P2;
      case 3:
        return PriorityLevel.P3;
      case 4:
        return PriorityLevel.P4;
      case 5:
        return PriorityLevel.P5;
      default:
        return PriorityLevel.P4;
    }

    // switch (priority) {
    // case "Show-stopper":
    // return PriorityLevel.P1;
    // case "Critical":
    // return PriorityLevel.P2;
    // case "Major":
    // return PriorityLevel.P3;
    // case "Normal":
    // return PriorityLevel.P4;
    // case "Minor":
    // return PriorityLevel.P5;
    // default:
    // return null;
    // }
  }

  public static String getRealIssueId(String pseudoIssueId, TaskRepository taskRepository) {
    if (pseudoIssueId.contains("-")) {
      return pseudoIssueId;
    } else {
      if (TasksUiPlugin.getTaskList().getTask(taskRepository.getRepositoryUrl(), pseudoIssueId) == null) {
        // Check if issue open from repository directly and not added into TaskList yet
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
          IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
          if (windows != null && windows.length > 0) {
            window = windows[0];
          }
        }
        IWorkbenchPage page = window.getActivePage();
        IEditorPart part = page.getActiveEditor();
        if (part instanceof TaskEditor) {
          TaskEditor taskPage = (TaskEditor) part;
          return taskPage.getTaskEditorInput().getTask().getTaskKey();
        }
        return null;
      }
      return TasksUiPlugin.getTaskList().getTask(taskRepository.getRepositoryUrl(), pseudoIssueId)
          .getTaskKey();
    }
  }
}
