package com.atlassian.plugins.tutorial.jira.reports;

import com.atlassian.core.util.map.EasyMap;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.project.ProjectService.GetProjectResult;
import com.atlassian.jira.charts.util.ChartReportUtils;
import com.atlassian.jira.charts.util.ChartUtils;
import com.atlassian.jira.exception.PermissionException;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.search.ReaderCache;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.issue.statistics.FilterStatisticsValuesGenerator;
import com.atlassian.jira.issue.statistics.StatisticsMapper;
import com.atlassian.jira.issue.statistics.StatsGroup;
import com.atlassian.jira.issue.statistics.util.OneDimensionalDocIssueHitCollector;
import com.atlassian.jira.plugin.report.impl.AbstractReport;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.SimpleErrorCollection;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.action.ProjectActionSupport;
import com.atlassian.jira.web.bean.FieldVisibilityBean;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.jira.web.util.OutlookDateManager;
import com.atlassian.util.profiling.UtilTimerStack;
import com.opensymphony.util.TextUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.search.Collector;

import javax.rmi.CORBA.Util;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


public class SingleLevelGroupByReportWithFilterProject extends AbstractReport
{
    private static final String PROJECT_OR_FILTER_ID = "projectOrFilterId";
    private static final Logger log                  = Logger.getLogger( SingleLevelGroupByReportWithFilterProject.class );

    private final SearchProvider                searchProvider;
    private final JiraAuthenticationContext     authenticationContext;
    private final SearchRequestService          searchRequestService;
    private final IssueFactory                  issueFactory;
    private final CustomFieldManager            customFieldManager;
    private final IssueIndexManager             issueIndexManager;
    private final SearchService                 searchService;
    private final FieldVisibilityManager        fieldVisibilityManager;
    private final ReaderCache                   readerCache;
    private final OutlookDateManager            outlookDateManager;
    private final ProjectService                projectService;
    private final ChartUtils                    chartUtils;

    public SingleLevelGroupByReportWithFilterProject ( final SearchProvider searchProvider, final JiraAuthenticationContext authenticationContext,
                                                       final SearchRequestService searchRequestService, final IssueFactory issueFactory,
                                                       final CustomFieldManager customFieldManager, final IssueIndexManager issueIndexManager,
                                                       final SearchService searchService, final FieldVisibilityManager fieldVisibilityManager,
                                                       final ReaderCache readerCache, final OutlookDateManager outlookDateManager,
                                                       final ProjectService projectService, final ChartUtils chartUtils) {
        this.searchProvider             = searchProvider;
        this.authenticationContext      = authenticationContext;
        this.searchRequestService       = searchRequestService;
        this.issueFactory               = issueFactory;
        this.customFieldManager         = customFieldManager;
        this.issueIndexManager          = issueIndexManager;
        this.searchService              = searchService;
        this.fieldVisibilityManager     = fieldVisibilityManager;
        this.readerCache                = readerCache;
        this.outlookDateManager         = outlookDateManager;
        this.projectService             = projectService;
        this.chartUtils                 = chartUtils;
    }


    public StatsGroup getOptions( SearchRequest sr, ApplicationUser user, StatisticsMapper mapper ) throws PermissionException {
        try {
            return searchMapIssueKeys( sr, user, mapper );
        }
        catch ( SearchException e ) {
            log.error( "Exception rendering " + this.getClass().getName() + ".  Exception " + e.getMessage(), e );
            return null;
        }
    }

    public StatsGroup searchMapIssueKeys( SearchRequest request, ApplicationUser searcher, StatisticsMapper mapper ) throws SearchException {
        try {
            UtilTimerStack.push( "Search Count Map" );
            StatsGroup statsGroup = new StatsGroup( mapper );
            Collector hitCollector = new OneDimensionalDocIssueHitCollector ( mapper.getDocumentConstant(),
                                                                              statsGroup,
                                                                              issueIndexManager.getIssueSearcher().getIndexReader(),
                                                                              issueFactory,
                                                                              fieldVisibilityManager,
                                                                              readerCache );
            searchProvider.searchAndSort( ( request != null ) ? request.getQuery() : null,
                                          searcher,
                                          hitCollector,
                                          PagerFilter.getUnlimitedFilter() );
            return statsGroup;
        }
        finally {
            UtilTimerStack.pop( "Search Count Map" );
        }
    }

    @Override
    public String generateReportHtml( ProjectActionSupport action, Map reqParams ) throws Exception {

        String projectOrFilterId = (String) reqParams.get( PROJECT_OR_FILTER_ID );
        String mapperName = (String) reqParams.get( "mapper" );

        if ( projectOrFilterId == null ) {
            log.error( "Single Level Group By Report run without a project selected (JRA-5042): params=" + reqParams );
            return "<span class='errMsg'>No search filter has been selected. Please "
                 + "<a href=\"IssueNavigator.jspa?reset=Update&amp;pid="
                 + TextUtils.htmlEncode((String) reqParams.get( PROJECT_OR_FILTER_ID ) )
                 + "\">create one</a>, and re-run this report. See also "
                 + "<a href=\"http://jira.atlassian.com/browse/JRA-5042\">JRA-5042</a></span>" ;
        }
        final StatisticsMapper mapper = new FilterStatisticsValuesGenerator().getStatsMapper( mapperName );

        final Map statsParams;

        statsParams = EasyMap.build(
                "action",               action,
                "mapperType",           mapperName,
                "customFieldManager",   customFieldManager,
                "fieldVisibility",      new FieldVisibilityBean(),
                "searchService",        searchService,
                "portlet",              this );

        final SearchRequest request = chartUtils.retrieveOrMakeSearchRequest( projectOrFilterId, statsParams );
        statsParams.put( "searchRequest", request );

        try {
            statsParams.put( "statsGroup", getOptions( request, authenticationContext.getUser(), mapper ) );

            statsParams.put( "outlookDate",
                                outlookDateManager.getOutlookDate( authenticationContext.getLocale() ) );
            return descriptor.getHtml( "view", statsParams );

        }
        catch ( PermissionException e ) {
            log.error( e, e );
            return null;
        }
    }

    @Override
    public void validate( ProjectActionSupport action, Map params ) {
        validateProjectOrFilterId( action, params );
    }

    protected void validateProjectOrFilterId( ProjectActionSupport action, Map params ) {
        String projectOrFilterId = (String) params.get( PROJECT_OR_FILTER_ID );

        if ( ChartReportUtils.isValidProjectParamFormat( projectOrFilterId ) ) {
            validateProjectId( action, ChartReportUtils.extractProjectOrFilterId( projectOrFilterId ) );
        }
        else if ( ChartReportUtils.isValidFilterParamFormat( projectOrFilterId ) ) {
            validateFilterId( action, ChartReportUtils.extractProjectOrFilterId( projectOrFilterId ) );
        }
        else {
            action.addError( PROJECT_OR_FILTER_ID, action.getText( "report.error.no.filter.or.project" ) );
        }
    }

    private void validateProjectId( ProjectActionSupport action, String projectId ) {
        try {
            if ( StringUtils.isNotEmpty( projectId ) ) {
                action.setSelectedProjectId( new Long( projectId ) );
            }
            Project project = action.getSelectedProjectObject();
            Collection<Project> browseable = action.getBrowsableProjects();

            if ( project == null || browseable == null || !browseable.contains( project ) ) {
                action.addError( PROJECT_OR_FILTER_ID, action.getText( "report.error.project.id.not.found" ) );
            }
        }
        catch ( NumberFormatException nfe ) {
            action.addError( PROJECT_OR_FILTER_ID, action.getText( "report.error.project.id.not.a.number", projectId ) );
        }
    }

    private void validateFilterId( ProjectActionSupport action, String filterId ) {
        try {
            JiraServiceContextImpl serviceContext = new JiraServiceContextImpl( action.getLoggedInUser(), new SimpleErrorCollection() );
            SearchRequest searchRequest = searchRequestService.getFilter( serviceContext, new Long( filterId ) );

            if (searchRequest == null) {
                action.addError( PROJECT_OR_FILTER_ID, action.getText( "report.error.no.filter" ) );
            }
        }
        catch ( NumberFormatException nfe ) {
            action.addError( PROJECT_OR_FILTER_ID, action.getText( "report.error.filter.id.not.a.number", filterId ) );
        }
    }
}
